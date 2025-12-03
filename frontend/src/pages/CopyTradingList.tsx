import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, Switch, message, Select, Dropdown, Divider, Spin } from 'antd'
import { PlusOutlined, DeleteOutlined, BarChartOutlined, UnorderedListOutlined, ArrowUpOutlined, ArrowDownOutlined } from '@ant-design/icons'
import type { MenuProps } from 'antd'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { CopyTrading, Leader, CopyTradingTemplate, CopyTradingStatistics } from '../types'
import { useMediaQuery } from 'react-responsive'
import { formatUSDC } from '../utils'

const { Option } = Select

const CopyTradingList: React.FC = () => {
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [copyTradings, setCopyTradings] = useState<CopyTrading[]>([])
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [templates, setTemplates] = useState<CopyTradingTemplate[]>([])
  const [loading, setLoading] = useState(false)
  const [statisticsMap, setStatisticsMap] = useState<Record<number, CopyTradingStatistics>>({})
  const [loadingStatistics, setLoadingStatistics] = useState<Set<number>>(new Set())
  const [filters, setFilters] = useState<{
    accountId?: number
    templateId?: number
    leaderId?: number
    enabled?: boolean
  }>({})
  
  useEffect(() => {
    fetchAccounts()
    fetchLeaders()
    fetchTemplates()
    fetchCopyTradings()
  }, [])
  
  useEffect(() => {
    fetchCopyTradings()
  }, [filters])
  
  const fetchLeaders = async () => {
    try {
      const response = await apiService.leaders.list({})
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      }
    } catch (error: any) {
      console.error('获取 Leader 列表失败:', error)
    }
  }
  
  const fetchTemplates = async () => {
    try {
      const response = await apiService.templates.list()
      if (response.data.code === 0 && response.data.data) {
        setTemplates(response.data.data.list || [])
      }
    } catch (error: any) {
      console.error('获取模板列表失败:', error)
    }
  }
  
  const fetchCopyTradings = async () => {
    setLoading(true)
    try {
      const response = await apiService.copyTrading.list(filters)
      if (response.data.code === 0 && response.data.data) {
        const list = response.data.data.list || []
        setCopyTradings(list)
        // 为每个跟单关系获取统计信息
        list.forEach((ct: CopyTrading) => {
          fetchStatistics(ct.id)
        })
      } else {
        message.error(response.data.msg || '获取跟单列表失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取跟单列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const fetchStatistics = async (copyTradingId: number) => {
    // 如果正在加载或已有数据，跳过
    if (loadingStatistics.has(copyTradingId) || statisticsMap[copyTradingId]) {
      return
    }
    
    setLoadingStatistics(prev => new Set(prev).add(copyTradingId))
    try {
      const response = await apiService.statistics.detail({ copyTradingId })
      if (response.data.code === 0 && response.data.data) {
        setStatisticsMap(prev => ({
          ...prev,
          [copyTradingId]: response.data.data
        }))
      }
    } catch (error: any) {
      console.error(`获取跟单统计失败: copyTradingId=${copyTradingId}`, error)
    } finally {
      setLoadingStatistics(prev => {
        const next = new Set(prev)
        next.delete(copyTradingId)
        return next
      })
    }
  }
  
  const getPnlColor = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '#666'
    return num >= 0 ? '#3f8600' : '#cf1322'
  }
  
  const getPnlIcon = (value: string) => {
    const num = parseFloat(value)
    if (isNaN(num)) return null
    return num >= 0 ? <ArrowUpOutlined /> : <ArrowDownOutlined />
  }
  
  const formatPercent = (value: string): string => {
    const num = parseFloat(value)
    if (isNaN(num)) return '-'
    return `${num >= 0 ? '+' : ''}${num.toFixed(2)}%`
  }
  
  const handleToggleStatus = async (copyTrading: CopyTrading) => {
    try {
      const response = await apiService.copyTrading.updateStatus({
        copyTradingId: copyTrading.id,
        enabled: !copyTrading.enabled
      })
      if (response.data.code === 0) {
        message.success(`${copyTrading.enabled ? '停止' : '开启'}跟单成功`)
        fetchCopyTradings()
      } else {
        message.error(response.data.msg || '更新跟单状态失败')
      }
    } catch (error: any) {
      message.error(error.message || '更新跟单状态失败')
    }
  }
  
  const handleDelete = async (copyTradingId: number) => {
    try {
      const response = await apiService.copyTrading.delete({ copyTradingId })
      if (response.data.code === 0) {
        message.success('删除跟单成功')
        fetchCopyTradings()
      } else {
        message.error(response.data.msg || '删除跟单失败')
      }
    } catch (error: any) {
      message.error(error.message || '删除跟单失败')
    }
  }
  
  const columns = [
    {
      title: '钱包',
      key: 'account',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => (
        <div>
          <div style={{ fontSize: isMobile ? 13 : 14, fontWeight: 500 }}>
            {record.accountName || `账户 ${record.accountId}`}
          </div>
          <div style={{ fontSize: isMobile ? 11 : 12, color: '#999', marginTop: 2 }}>
            {isMobile 
              ? `${record.walletAddress.slice(0, 4)}...${record.walletAddress.slice(-3)}`
              : `${record.walletAddress.slice(0, 6)}...${record.walletAddress.slice(-4)}`
            }
          </div>
        </div>
      )
    },
    {
      title: '模板',
      dataIndex: 'templateName',
      key: 'templateName',
      width: isMobile ? 100 : 120,
      render: (text: string) => (
        <strong style={{ fontSize: isMobile ? 13 : 14 }}>{text}</strong>
      )
    },
    {
      title: 'Leader',
      key: 'leader',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => (
        <div>
          <div style={{ fontSize: isMobile ? 13 : 14, fontWeight: 500 }}>
            {record.leaderName || `Leader ${record.leaderId}`}
          </div>
          <div style={{ fontSize: isMobile ? 11 : 12, color: '#999', marginTop: 2 }}>
            {isMobile 
              ? `${record.leaderAddress.slice(0, 4)}...${record.leaderAddress.slice(-3)}`
              : `${record.leaderAddress.slice(0, 6)}...${record.leaderAddress.slice(-4)}`
            }
          </div>
        </div>
      )
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: isMobile ? 80 : 100,
      render: (enabled: boolean, record: CopyTrading) => (
        <Switch
          checked={enabled}
          onChange={() => handleToggleStatus(record)}
          checkedChildren="开启"
          unCheckedChildren="停止"
        />
      )
    },
    {
      title: '总盈亏',
      key: 'totalPnl',
      width: isMobile ? 100 : 150,
      render: (_: any, record: CopyTrading) => {
        const stats = statisticsMap[record.id]
        if (!stats) {
          return loadingStatistics.has(record.id) ? (
            <span style={{ fontSize: isMobile ? 11 : 12 }}>加载中...</span>
          ) : (
            <span style={{ fontSize: isMobile ? 11 : 12 }}>-</span>
          )
        }
        return (
          <div>
            <div style={{ 
              color: getPnlColor(stats.totalPnl), 
              fontWeight: 500,
              display: 'flex',
              alignItems: 'center',
              gap: 4,
              fontSize: isMobile ? 12 : 14
            }}>
              {getPnlIcon(stats.totalPnl)}
              {isMobile ? formatUSDC(stats.totalPnl) : `${formatUSDC(stats.totalPnl)} USDC`}
            </div>
            {!isMobile && (
              <div style={{ 
                fontSize: 12, 
                color: getPnlColor(stats.totalPnlPercent),
                marginTop: 4
              }}>
                {formatPercent(stats.totalPnlPercent)}
              </div>
            )}
          </div>
        )
      }
    },
    {
      title: '操作',
      key: 'action',
      width: isMobile ? 100 : 200,
      fixed: 'right' as const,
      render: (_: any, record: CopyTrading) => {
        const menuItems: MenuProps['items'] = [
          {
            key: 'statistics',
            label: '查看统计',
            icon: <BarChartOutlined />,
            onClick: () => navigate(`/copy-trading/statistics/${record.id}`)
          },
          {
            key: 'buyOrders',
            label: '买入订单',
            icon: <UnorderedListOutlined />,
            onClick: () => navigate(`/copy-trading/orders/buy/${record.id}`)
          },
          {
            key: 'sellOrders',
            label: '卖出订单',
            icon: <UnorderedListOutlined />,
            onClick: () => navigate(`/copy-trading/orders/sell/${record.id}`)
          },
          {
            key: 'matchedOrders',
            label: '匹配关系',
            icon: <UnorderedListOutlined />,
            onClick: () => navigate(`/copy-trading/orders/matched/${record.id}`)
          },
          {
            type: 'divider'
          },
          {
            key: 'delete',
            label: (
              <Popconfirm
                title="确定要删除这个跟单关系吗？"
                onConfirm={() => handleDelete(record.id)}
                okText="确定"
                cancelText="取消"
                onCancel={(e) => e?.stopPropagation()}
              >
                <span style={{ color: '#ff4d4f' }}>删除</span>
              </Popconfirm>
            ),
            danger: true
          }
        ]
        
        return (
          <Space size={isMobile ? 'small' : 'middle'} wrap>
            {!isMobile && (
              <Button
                type="link"
                size="small"
                icon={<BarChartOutlined />}
                onClick={() => navigate(`/copy-trading/statistics/${record.id}`)}
              >
                统计
              </Button>
            )}
            <Dropdown menu={{ items: menuItems }} trigger={['click']}>
              <Button
                type="link"
                size="small"
                icon={<UnorderedListOutlined />}
              >
                {isMobile ? '' : '订单'}
              </Button>
            </Dropdown>
            {!isMobile && (
              <Popconfirm
                title="确定要删除这个跟单关系吗？"
                onConfirm={() => handleDelete(record.id)}
                okText="确定"
                cancelText="取消"
              >
                <Button
                  type="link"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                >
                  删除
                </Button>
              </Popconfirm>
            )}
          </Space>
        )
      }
    }
  ]
  
  return (
    <div>
      <Card>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
          <h2 style={{ margin: 0 }}>跟单配置管理</h2>
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => navigate('/copy-trading/add')}
          >
            新增跟单
          </Button>
        </div>
        
        <div style={{ marginBottom: 16, display: 'flex', gap: 16, flexWrap: 'wrap' }}>
          <Select
            placeholder="筛选钱包"
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.accountId}
            onChange={(value) => setFilters({ ...filters, accountId: value || undefined })}
          >
            {accounts.map(account => (
              <Option key={account.id} value={account.id}>
                {account.accountName || `账户 ${account.id}`}
              </Option>
            ))}
          </Select>
          
          <Select
            placeholder="筛选模板"
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.templateId}
            onChange={(value) => setFilters({ ...filters, templateId: value || undefined })}
          >
            {templates.map(template => (
              <Option key={template.id} value={template.id}>
                {template.templateName}
              </Option>
            ))}
          </Select>
          
          <Select
            placeholder="筛选 Leader"
            allowClear
            style={{ width: isMobile ? '100%' : 200 }}
            value={filters.leaderId}
            onChange={(value) => setFilters({ ...filters, leaderId: value || undefined })}
          >
            {leaders.map(leader => (
              <Option key={leader.id} value={leader.id}>
                {leader.leaderName || `Leader ${leader.id}`}
              </Option>
            ))}
          </Select>
          
          <Select
            placeholder="筛选状态"
            allowClear
            style={{ width: isMobile ? '100%' : 150 }}
            value={filters.enabled}
            onChange={(value) => setFilters({ ...filters, enabled: value !== undefined ? value : undefined })}
          >
            <Option value={true}>开启</Option>
            <Option value={false}>停止</Option>
          </Select>
        </div>
        
        {isMobile ? (
          // 移动端卡片布局
          <div>
            {loading ? (
              <div style={{ textAlign: 'center', padding: '40px' }}>
                <Spin size="large" />
              </div>
            ) : copyTradings.length === 0 ? (
              <div style={{ textAlign: 'center', padding: '40px', color: '#999' }}>
                暂无跟单配置
              </div>
            ) : (
              <div style={{ display: 'flex', flexDirection: 'column', gap: '12px' }}>
                {copyTradings.map((record) => {
                  const stats = statisticsMap[record.id]
                  const date = new Date(record.createdAt)
                  const formattedDate = date.toLocaleString('zh-CN', {
                    year: 'numeric',
                    month: '2-digit',
                    day: '2-digit',
                    hour: '2-digit',
                    minute: '2-digit'
                  })
                  
                  return (
                    <Card
                      key={record.id}
                      style={{
                        borderRadius: '12px',
                        boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
                        border: '1px solid #e8e8e8'
                      }}
                      bodyStyle={{ padding: '16px' }}
                    >
                      {/* 基本信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ 
                          fontSize: '16px', 
                          fontWeight: 'bold', 
                          marginBottom: '8px',
                          color: '#1890ff'
                        }}>
                          {record.templateName}
                        </div>
                        <div style={{ display: 'flex', flexWrap: 'wrap', gap: '6px', alignItems: 'center', justifyContent: 'space-between' }}>
                          <Tag color={record.enabled ? 'green' : 'red'}>
                            {record.enabled ? '启用' : '禁用'}
                          </Tag>
                          <Switch
                            checked={record.enabled}
                            onChange={() => handleToggleStatus(record)}
                            checkedChildren="开启"
                            unCheckedChildren="停止"
                            size="small"
                          />
                        </div>
                      </div>
                      
                      <Divider style={{ margin: '12px 0' }} />
                      
                      {/* 账户信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>账户</div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          {record.accountName || `账户 ${record.accountId}`}
                        </div>
                        <div style={{ fontSize: '12px', color: '#999', marginTop: '2px' }}>
                          {record.walletAddress.slice(0, 6)}...{record.walletAddress.slice(-4)}
                        </div>
                      </div>
                      
                      {/* Leader 信息 */}
                      <div style={{ marginBottom: '12px' }}>
                        <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>Leader</div>
                        <div style={{ fontSize: '14px', fontWeight: '500' }}>
                          {record.leaderName || `Leader ${record.leaderId}`}
                        </div>
                        <div style={{ fontSize: '12px', color: '#999', marginTop: '2px' }}>
                          {record.leaderAddress.slice(0, 6)}...{record.leaderAddress.slice(-4)}
                        </div>
                      </div>
                      
                      {/* 总盈亏 */}
                      {stats && (
                        <div style={{ marginBottom: '12px' }}>
                          <div style={{ fontSize: '12px', color: '#666', marginBottom: '4px' }}>总盈亏</div>
                          <div style={{ 
                            fontSize: '16px', 
                            fontWeight: 'bold',
                            color: getPnlColor(stats.totalPnl),
                            display: 'flex',
                            alignItems: 'center',
                            gap: '4px'
                          }}>
                            {getPnlIcon(stats.totalPnl)}
                            {formatUSDC(stats.totalPnl)} USDC
                          </div>
                          <div style={{ 
                            fontSize: '12px', 
                            color: getPnlColor(stats.totalPnlPercent),
                            marginTop: '4px'
                          }}>
                            {formatPercent(stats.totalPnlPercent)}
                          </div>
                        </div>
                      )}
                      
                      {loadingStatistics.has(record.id) && (
                        <div style={{ marginBottom: '12px', fontSize: '12px', color: '#999' }}>
                          加载统计中...
                        </div>
                      )}
                      
                      {/* 创建时间 */}
                      <div style={{ marginBottom: '16px' }}>
                        <div style={{ fontSize: '12px', color: '#999' }}>
                          创建时间: {formattedDate}
                        </div>
                      </div>
                      
                      {/* 操作按钮 */}
                      <div style={{ display: 'flex', gap: '8px', flexWrap: 'wrap' }}>
                        <Button
                          type="primary"
                          size="small"
                          icon={<BarChartOutlined />}
                          onClick={() => navigate(`/copy-trading/statistics/${record.id}`)}
                          style={{ flex: 1, minWidth: '80px' }}
                        >
                          统计
                        </Button>
                        <Dropdown 
                          menu={{ 
                            items: [
                              {
                                key: 'statistics',
                                label: '查看统计',
                                icon: <BarChartOutlined />,
                                onClick: () => navigate(`/copy-trading/statistics/${record.id}`)
                              },
                              {
                                key: 'buyOrders',
                                label: '买入订单',
                                icon: <UnorderedListOutlined />,
                                onClick: () => navigate(`/copy-trading/orders/buy/${record.id}`)
                              },
                              {
                                key: 'sellOrders',
                                label: '卖出订单',
                                icon: <UnorderedListOutlined />,
                                onClick: () => navigate(`/copy-trading/orders/sell/${record.id}`)
                              },
                              {
                                key: 'matchedOrders',
                                label: '匹配关系',
                                icon: <UnorderedListOutlined />,
                                onClick: () => navigate(`/copy-trading/orders/matched/${record.id}`)
                              }
                            ]
                          }} 
                          trigger={['click']}
                        >
                          <Button
                            size="small"
                            icon={<UnorderedListOutlined />}
                            style={{ flex: 1, minWidth: '80px' }}
                          >
                            订单
                          </Button>
                        </Dropdown>
                        <Popconfirm
                          title="确定要删除这个跟单关系吗？"
                          onConfirm={() => handleDelete(record.id)}
                          okText="确定"
                          cancelText="取消"
                        >
                          <Button
                            danger
                            size="small"
                            icon={<DeleteOutlined />}
                            style={{ flex: 1, minWidth: '80px' }}
                          >
                            删除
                          </Button>
                        </Popconfirm>
                      </div>
                    </Card>
                  )
                })}
              </div>
            )}
          </div>
        ) : (
          // 桌面端表格布局
          <Table
            columns={columns}
            dataSource={copyTradings}
            rowKey="id"
            loading={loading}
            pagination={{
              pageSize: 20,
              showSizeChanger: true,
              showTotal: (total) => `共 ${total} 条`
            }}
          />
        )}
      </Card>
    </div>
  )
}

export default CopyTradingList

