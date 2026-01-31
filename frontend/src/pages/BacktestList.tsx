import { useState, useEffect } from 'react'
import { Table, Card, Button, Select, Tag, Space, Modal, message, Row, Col, Form, Input, InputNumber, Switch } from 'antd'
import { useTranslation } from 'react-i18next'
import { useNavigate } from 'react-router-dom'
import { PlusOutlined, ReloadOutlined, DeleteOutlined, StopOutlined, EyeOutlined, RedoOutlined } from '@ant-design/icons'
import { formatUSDC } from '../utils'
import { backtestService, apiService } from '../services/api'
import type { BacktestTaskDto, BacktestListRequest, BacktestCreateRequest } from '../types/backtest'
import type { Leader } from '../types'
import { useMediaQuery } from 'react-responsive'

const { Option } = Select

const BacktestList: React.FC = () => {
  const { t } = useTranslation()
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [loading, setLoading] = useState(false)
  const [tasks, setTasks] = useState<BacktestTaskDto[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size] = useState(10)
  const [statusFilter, setStatusFilter] = useState<string | undefined>()
  const [leaderIdFilter] = useState<number | undefined>()
  const [sortBy, setSortBy] = useState<'profitAmount' | 'profitRate' | 'createdAt'>('createdAt')
  const [sortOrder, setSortOrder] = useState<'asc' | 'desc'>('desc')

  // 创建回测 modal 相关状态
  const [createModalVisible, setCreateModalVisible] = useState(false)
  const [createForm] = Form.useForm()
  const [createLoading, setCreateLoading] = useState(false)
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [copyMode, setCopyMode] = useState<'RATIO' | 'FIXED'>('RATIO')

  // 获取回测任务列表
  const fetchTasks = async () => {
    setLoading(true)
    try {
      const request: BacktestListRequest = {
        leaderId: leaderIdFilter,
        status: statusFilter as any,
        sortBy,
        sortOrder,
        page,
        size
      }
      const response = await backtestService.list(request)
      if (response.data.code === 0 && response.data.data) {
        setTasks(response.data.data.list)
        setTotal(response.data.data.total)
      } else {
        message.error(response.data.msg || t('backtest.fetchTasksFailed'))
      }
    } catch (error) {
      console.error('Failed to fetch backtest tasks:', error)
      message.error(t('backtest.fetchTasksFailed'))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    fetchTasks()
  }, [page, statusFilter, leaderIdFilter, sortBy, sortOrder])

  // 刷新
  const handleRefresh = () => {
    fetchTasks()
  }

  // 删除任务
  const handleDelete = (id: number) => {
    Modal.confirm({
      title: t('backtest.deleteConfirm'),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const response = await backtestService.delete({ id })
          if (response.data.code === 0) {
            message.success(t('backtest.deleteSuccess'))
            fetchTasks()
          } else {
            message.error(response.data.msg || t('backtest.deleteFailed'))
          }
        } catch (error) {
          console.error('Failed to delete backtest task:', error)
          message.error(t('backtest.deleteFailed'))
        }
      }
    })
  }

  // 停止任务
  const handleStop = (id: number) => {
    Modal.confirm({
      title: t('backtest.stopConfirm'),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const response = await backtestService.stop({ id })
          if (response.data.code === 0) {
            message.success(t('backtest.stopSuccess'))
            fetchTasks()
          } else {
            message.error(response.data.msg || t('backtest.stopFailed'))
          }
        } catch (error) {
          console.error('Failed to stop backtest task:', error)
          message.error(t('backtest.stopFailed'))
        }
      }
    })
  }

  // 重试任务
  const handleRetry = (id: number) => {
    Modal.confirm({
      title: t('backtest.retryConfirm'),
      okText: t('common.confirm'),
      cancelText: t('common.cancel'),
      onOk: async () => {
        try {
          const response = await backtestService.retry({ id })
          if (response.data.code === 0) {
            message.success(t('backtest.retrySuccess'))
            fetchTasks()
          } else {
            message.error(response.data.msg || t('backtest.retryFailed'))
          }
        } catch (error) {
          console.error('Failed to retry backtest task:', error)
          message.error(t('backtest.retryFailed'))
        }
      }
    })
  }

  // 获取 Leader 列表
  useEffect(() => {
    if (createModalVisible) {
      const fetchLeaders = async () => {
        try {
          const response = await apiService.leaders.list({})
          if (response.data.code === 0 && response.data.data) {
            setLeaders(response.data.data.list || [])
          }
        } catch (error) {
          console.error('Failed to fetch leaders:', error)
        }
      }
      fetchLeaders()
    }
  }, [createModalVisible])

  // 查看详情
  const handleViewDetail = (id: number) => {
    navigate(`/backtest/detail/${id}`)
  }

  // 打开创建 modal
  const handleCreate = () => {
    setCreateModalVisible(true)
    createForm.resetFields()
    createForm.setFieldsValue({
      copyMode: 'RATIO',
      copyRatio: 100, // 默认 100%（显示为百分比）
      maxOrderSize: 1000,
      minOrderSize: 1,
      maxDailyLoss: 500,
      maxDailyOrders: 50,
      supportSell: true,
      keywordFilterMode: 'DISABLED',
      backtestDays: 7
    })
    setCopyMode('RATIO')
  }

  // 提交创建回测任务
  const handleCreateSubmit = async () => {
    try {
      const values = await createForm.validateFields()
      setCreateLoading(true)

      const request: BacktestCreateRequest = {
        taskName: values.taskName,
        leaderId: values.leaderId,
        initialBalance: values.initialBalance,
        backtestDays: values.backtestDays,
        copyMode: values.copyMode || 'RATIO',
        copyRatio: values.copyMode === 'RATIO' && values.copyRatio ? (values.copyRatio / 100).toString() : undefined,
        fixedAmount: values.copyMode === 'FIXED' ? values.fixedAmount : undefined,
        maxOrderSize: values.maxOrderSize,
        minOrderSize: values.minOrderSize,
        maxDailyLoss: values.maxDailyLoss,
        maxDailyOrders: values.maxDailyOrders,
        supportSell: values.supportSell,
        keywordFilterMode: values.keywordFilterMode,
        keywords: values.keywords
      }

      const response = await backtestService.create(request)
      if (response.data.code === 0) {
        message.success(t('backtest.createSuccess'))
        setCreateModalVisible(false)
        createForm.resetFields()
        fetchTasks()
      } else {
        message.error(response.data.msg || t('backtest.createFailed'))
      }
    } catch (error) {
      console.error('Failed to create backtest task:', error)
      message.error(t('backtest.createFailed'))
    } finally {
      setCreateLoading(false)
    }
  }

  // 状态标签颜色
  const getStatusColor = (status: string) => {
    switch (status) {
      case 'PENDING': return 'blue'
      case 'RUNNING': return 'processing'
      case 'COMPLETED': return 'success'
      case 'STOPPED': return 'warning'
      case 'FAILED': return 'error'
      default: return 'default'
    }
  }

  // 状态标签文本
  const getStatusText = (status: string) => {
    switch (status) {
      case 'PENDING': return t('backtest.statusPending')
      case 'RUNNING': return t('backtest.statusRunning')
      case 'COMPLETED': return t('backtest.statusCompleted')
      case 'STOPPED': return t('backtest.statusStopped')
      case 'FAILED': return t('backtest.statusFailed')
      default: return status
    }
  }

  const columns = [
    {
      title: t('backtest.taskName'),
      dataIndex: 'taskName',
      key: 'taskName',
      width: isMobile ? 120 : 150
    },
    {
      title: t('backtest.leader'),
      dataIndex: 'leaderName',
      key: 'leaderName',
      width: isMobile ? 100 : 150,
      render: (_: any, record: BacktestTaskDto) => record.leaderName || record.leaderAddress?.substring(0, 8) + '...' || '-'
    },
    {
      title: t('backtest.initialBalance'),
      dataIndex: 'initialBalance',
      key: 'initialBalance',
      width: 120,
      render: (value: string) => formatUSDC(value)
    },
    {
      title: t('backtest.finalBalance'),
      dataIndex: 'finalBalance',
      key: 'finalBalance',
      width: 120,
      render: (value: string | null) => value ? formatUSDC(value) : '-'
    },
    {
      title: t('backtest.profitAmount'),
      dataIndex: 'profitAmount',
      key: 'profitAmount',
      width: 120,
      render: (value: string | null) => value ? (
        <span style={{ color: parseFloat(value) >= 0 ? '#52c41a' : '#ff4d4f' }}>
          {formatUSDC(value)}
        </span>
      ) : '-'
    },
    {
      title: t('backtest.profitRate'),
      dataIndex: 'profitRate',
      key: 'profitRate',
      width: 100,
      render: (value: string | null) => value ? (
        <span style={{ color: parseFloat(value) >= 0 ? '#52c41a' : '#ff4d4f' }}>
          {value}%
        </span>
      ) : '-'
    },
    {
      title: t('backtest.backtestDays'),
      dataIndex: 'backtestDays',
      key: 'backtestDays',
      width: 100,
      render: (value: number) => `${value} ${t('common.day')}`
    },
    {
      title: t('backtest.status'),
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (status: string) => (
        <Tag color={getStatusColor(status)}>{getStatusText(status)}</Tag>
      )
    },
    {
      title: t('backtest.progress'),
      dataIndex: 'progress',
      key: 'progress',
      width: 120,
      render: (progress: number) => (
        <div style={{ width: '100%' }}>
          <div style={{ marginBottom: 4 }}>{progress}%</div>
          <div style={{ width: '100%', height: 6, backgroundColor: '#f0f0f0', borderRadius: 3 }}>
            <div
              style={{
                width: `${progress}%`,
                height: '100%',
                backgroundColor: progress === 100 ? '#52c41a' : '#1890ff',
                borderRadius: 3,
                transition: 'width 0.3s ease'
              }}
            />
          </div>
        </div>
      )
    },
    {
      title: t('backtest.totalTrades'),
      dataIndex: 'totalTrades',
      key: 'totalTrades',
      width: 100
    },
    {
      title: t('backtest.createdAt'),
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: isMobile ? 150 : 180,
      render: (timestamp: number) => new Date(timestamp).toLocaleString()
    },
    {
      title: t('common.actions'),
      key: 'actions',
      fixed: isMobile ? false : ('right' as const),
      width: isMobile ? 100 : 150,
      render: (_: any, record: BacktestTaskDto) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => handleViewDetail(record.id)}
          >
            {t('common.viewDetail')}
          </Button>
          {record.status === 'RUNNING' && (
            <Button
              type="link"
              size="small"
              danger
              icon={<StopOutlined />}
              onClick={() => handleStop(record.id)}
            >
              {t('backtest.stop')}
            </Button>
          )}
          {(record.status === 'STOPPED' || record.status === 'FAILED') && (
            <Button
              type="link"
              size="small"
              icon={<RedoOutlined />}
              onClick={() => handleRetry(record.id)}
            >
              {t('backtest.retry')}
            </Button>
          )}
          {(record.status === 'PENDING' || record.status === 'COMPLETED') && (
            <Button
              type="link"
              size="small"
              danger
              icon={<DeleteOutlined />}
              onClick={() => handleDelete(record.id)}
            >
              {t('common.delete')}
            </Button>
          )}
        </Space>
      )
    }
  ]

  return (
    <div style={{ padding: 24 }}>
      <Card>
        <Space direction="vertical" size="large" style={{ width: '100%' }}>
          {/* 头部操作栏 */}
          <Row justify="space-between" align="middle" gutter={[16, 16]}>
            <Col xs={24} sm={24} md={12} lg={16}>
              <Space size="middle" direction={isMobile ? 'vertical' : 'horizontal'} style={{ width: isMobile ? '100%' : 'auto' }}>
                <Select
                  style={{ width: isMobile ? '100%' : 150 }}
                  placeholder={t('backtest.status')}
                  allowClear
                  onChange={(value) => setStatusFilter(value)}
                  value={statusFilter}
                >
                  <Select.Option value="PENDING">{t('backtest.statusPending')}</Select.Option>
                  <Select.Option value="RUNNING">{t('backtest.statusRunning')}</Select.Option>
                  <Select.Option value="COMPLETED">{t('backtest.statusCompleted')}</Select.Option>
                  <Select.Option value="STOPPED">{t('backtest.statusStopped')}</Select.Option>
                  <Select.Option value="FAILED">{t('backtest.statusFailed')}</Select.Option>
                </Select>
                <Select
                  style={{ width: isMobile ? '100%' : 150 }}
                  placeholder={t('backtest.sortBy')}
                  onChange={(value) => setSortBy(value)}
                  value={sortBy}
                >
                  <Select.Option value="profitAmount">{t('backtest.profitAmount')}</Select.Option>
                  <Select.Option value="profitRate">{t('backtest.profitRate')}</Select.Option>
                  <Select.Option value="createdAt">{t('backtest.createdAt')}</Select.Option>
                </Select>
                <Select
                  style={{ width: isMobile ? '100%' : 120 }}
                  placeholder={t('backtest.sortOrder')}
                  onChange={(value) => setSortOrder(value)}
                  value={sortOrder}
                >
                  <Select.Option value="asc">{t('common.ascending')}</Select.Option>
                  <Select.Option value="desc">{t('common.descending')}</Select.Option>
                </Select>
              </Space>
            </Col>
            <Col xs={24} sm={24} md={12} lg={8} style={{ textAlign: isMobile ? 'left' : 'right' }}>
              <Space style={{ width: isMobile ? '100%' : 'auto' }}>
                <Button
                  type="primary"
                  icon={<PlusOutlined />}
                  onClick={handleCreate}
                  style={{ flex: isMobile ? 1 : undefined }}
                >
                  {isMobile ? t('common.create') : t('backtest.createTask')}
                </Button>
                <Button
                  icon={<ReloadOutlined />}
                  onClick={handleRefresh}
                  loading={loading}
                  style={{ flex: isMobile ? 1 : undefined }}
                >
                  {t('common.refresh')}
                </Button>
              </Space>
            </Col>
          </Row>

          {/* 数据表格 */}
          <Table
            columns={columns}
            dataSource={tasks}
            rowKey="id"
            loading={loading}
            pagination={{
              current: page,
              pageSize: size,
              total,
              showSizeChanger: false,
              showTotal: (total) => `${t('common.total')} ${total} ${t('common.items')}`,
              onChange: (newPage) => setPage(newPage),
              simple: isMobile
            }}
            scroll={isMobile ? { x: 1200 } : { x: 1400 }}
          />
        </Space>
      </Card>

      {/* 创建回测任务 Modal */}
      <Modal
        title={t('backtest.createTask')}
        open={createModalVisible}
        onCancel={() => {
          setCreateModalVisible(false)
          createForm.resetFields()
        }}
        onOk={handleCreateSubmit}
        okText={t('common.save')}
        cancelText={t('common.cancel')}
        width={isMobile ? '95%' : 800}
        confirmLoading={createLoading}
        destroyOnClose
        style={{ top: isMobile ? 10 : 20 }}
        bodyStyle={{ maxHeight: isMobile ? 'calc(100vh - 150px)' : 'calc(100vh - 200px)', overflowY: 'auto' }}
      >
        <Form
          form={createForm}
          layout="vertical"
          initialValues={{
            maxDailyLoss: 500,
            maxDailyOrders: 50,
            supportSell: true,
            keywordFilterMode: 'DISABLED',
            backtestDays: 7
          }}
        >
          <Row gutter={24}>
            <Col xs={24} sm={24} md={12}>
              <Form.Item
                label={t('backtest.taskName')}
                name="taskName"
                rules={[{ required: true, message: t('backtest.taskNameRequired') || '请输入任务名称' }]}
              >
                <Input placeholder={t('backtest.taskName')} />
              </Form.Item>
            </Col>
            <Col xs={24} sm={24} md={12}>
              <Form.Item
                label={t('backtest.leader')}
                name="leaderId"
                rules={[{ required: true, message: t('backtest.leaderRequired') || '请选择 Leader' }]}
              >
                <Select placeholder={t('backtest.leader')} showSearch>
                  {leaders.map((leader) => (
                    <Option key={leader.id} value={leader.id}>
                      {leader.leaderName || leader.leaderAddress}
                    </Option>
                  ))}
                </Select>
              </Form.Item>
            </Col>
          </Row>

          <Row gutter={24}>
            <Col xs={24} sm={24} md={12}>
              <Form.Item
                label={t('backtest.initialBalance') + ' (USDC)'}
                name="initialBalance"
                rules={[
                  { required: true, message: t('backtest.initialBalanceRequired') || '请输入初始资金' },
                  { type: 'number', min: 1, message: t('backtest.initialBalanceInvalid') || '初始资金必须大于 0' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.initialBalance')}
                  precision={2}
                  min={1}
                />
              </Form.Item>
            </Col>
            <Col xs={24} sm={24} md={12}>
              <Form.Item
                label={t('backtest.backtestDays') + ` (1-15 ${t('common.day')})`}
                name="backtestDays"
                rules={[
                  { required: true, message: t('backtest.backtestDaysRequired') || '请输入回测天数' },
                  { type: 'number', min: 1, max: 15, message: t('backtest.backtestDaysInvalid') || '回测天数必须在 1-15 之间' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.backtestDays')}
                  precision={0}
                  min={1}
                  max={15}
                />
              </Form.Item>
            </Col>
          </Row>

          {/* 跟单配置 */}
          <div style={{ marginBottom: 24 }}>
            <h3 style={{ marginBottom: 16 }}>{t('backtest.config')}</h3>

            <Form.Item
              label={t('backtest.copyMode')}
              name="copyMode"
            >
              <Select onChange={(value) => setCopyMode(value)}>
                <Option value="RATIO">{t('backtest.copyModeRatio')}</Option>
                <Option value="FIXED">{t('backtest.copyModeFixed')}</Option>
              </Select>
            </Form.Item>

            {copyMode === 'RATIO' && (
              <Form.Item
                label={t('backtest.copyRatio')}
                name="copyRatio"
                tooltip={t('backtest.copyRatioTooltip') || '跟单比例表示跟单金额相对于 Leader 订单金额的百分比。例如：100% 表示 1:1 跟单，50% 表示半仓跟单，200% 表示双倍跟单'}
                rules={[
                  { required: true, message: t('backtest.copyRatioRequired') || '请输入跟单比例' },
                  { type: 'number', min: 0.01, max: 10000, message: t('backtest.copyRatioInvalid') || '跟单比例必须在 0.01-10000 之间' }
                ]}
              >
                <InputNumber
                  min={0.01}
                  max={10000}
                  step={0.01}
                  precision={2}
                  style={{ width: '100%' }}
                  addonAfter="%"
                  placeholder={t('backtest.copyRatioPlaceholder') || '例如：100 表示 100%（1:1 跟单），默认 100%'}
                  parser={(value) => {
                    const parsed = parseFloat(value || '0')
                    if (parsed > 10000) return 10000
                    return parsed
                  }}
                  formatter={(value) => {
                    if (!value && value !== 0) return ''
                    const num = parseFloat(value.toString())
                    if (isNaN(num)) return ''
                    if (num > 10000) return '10000'
                    return num.toString().replace(/\.0+$/, '')
                  }}
                />
              </Form.Item>
            )}

            {copyMode === 'FIXED' && (
              <Form.Item
                label={t('backtest.fixedAmount') + ' (USDC)'}
                name="fixedAmount"
                rules={[
                  { required: true, message: t('backtest.fixedAmountRequired') || '请输入固定金额' },
                  { type: 'number', min: 1, message: t('backtest.fixedAmountInvalid') || '固定金额必须大于 0' }
                ]}
              >
                <InputNumber
                  style={{ width: '100%' }}
                  placeholder={t('backtest.fixedAmount')}
                  precision={2}
                  min={1}
                />
              </Form.Item>
            )}

            <Row gutter={24}>
              <Col xs={24} sm={24} md={12}>
                <Form.Item
                  label={t('backtest.maxOrderSize') + ' (USDC)'}
                  name="maxOrderSize"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={1} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Form.Item
                  label={t('backtest.minOrderSize') + ' (USDC)'}
                  name="minOrderSize"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={1} />
                </Form.Item>
              </Col>
            </Row>

            <Row gutter={24}>
              <Col xs={24} sm={24} md={12}>
                <Form.Item
                  label={t('backtest.maxDailyLoss') + ' (USDC)'}
                  name="maxDailyLoss"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={2} min={0} />
                </Form.Item>
              </Col>
              <Col xs={24} sm={24} md={12}>
                <Form.Item
                  label={t('backtest.maxDailyOrders')}
                  name="maxDailyOrders"
                  rules={[{ required: true }]}
                >
                  <InputNumber style={{ width: '100%' }} precision={0} min={1} />
                </Form.Item>
              </Col>
            </Row>

            <Form.Item
              label={t('backtest.supportSell')}
              name="supportSell"
              valuePropName="checked"
            >
              <Switch />
              <span style={{ fontSize: 12, color: '#888', marginLeft: 8 }}>{t('backtest.supportSellHint') || '是否跟随 Leader 卖出'}</span>
            </Form.Item>

            <Form.Item
              label={t('backtest.keywordFilterMode')}
              name="keywordFilterMode"
            >
              <Select>
                <Option value="DISABLED">{t('backtest.keywordFilterModeDisabled')}</Option>
                <Option value="WHITELIST">{t('backtest.keywordFilterModeWhitelist')}</Option>
                <Option value="BLACKLIST">{t('backtest.keywordFilterModeBlacklist')}</Option>
              </Select>
            </Form.Item>

            <Form.Item
              label={t('backtest.keywords')}
              name="keywords"
            >
              <Select
                mode="tags"
                style={{ width: '100%' }}
                placeholder={t('backtest.keywordsPlaceholder') || '请输入关键字，按回车添加'}
              />
            </Form.Item>
          </div>
        </Form>
      </Modal>
    </div >
  )
}

export default BacktestList
