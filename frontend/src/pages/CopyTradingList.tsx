import { useEffect, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, Switch, message, Select, Input } from 'antd'
import { PlusOutlined, DeleteOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useAccountStore } from '../store/accountStore'
import type { CopyTrading, Account, Leader, CopyTradingTemplate } from '../types'
import { useMediaQuery } from 'react-responsive'

const { Option } = Select

const CopyTradingList: React.FC = () => {
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const { accounts, fetchAccounts } = useAccountStore()
  const [copyTradings, setCopyTradings] = useState<CopyTrading[]>([])
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [templates, setTemplates] = useState<CopyTradingTemplate[]>([])
  const [loading, setLoading] = useState(false)
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
        setCopyTradings(response.data.data.list || [])
      } else {
        message.error(response.data.msg || '获取跟单列表失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取跟单列表失败')
    } finally {
      setLoading(false)
    }
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
      render: (_: any, record: CopyTrading) => (
        <div>
          <div>{record.accountName || `账户 ${record.accountId}`}</div>
          <div style={{ fontSize: 12, color: '#999' }}>
            {record.walletAddress.slice(0, 6)}...{record.walletAddress.slice(-4)}
          </div>
        </div>
      )
    },
    {
      title: '模板',
      dataIndex: 'templateName',
      key: 'templateName',
      render: (text: string) => <strong>{text}</strong>
    },
    {
      title: 'Leader',
      key: 'leader',
      render: (_: any, record: CopyTrading) => (
        <div>
          <div>{record.leaderName || `Leader ${record.leaderId}`}</div>
          <div style={{ fontSize: 12, color: '#999' }}>
            {record.leaderAddress.slice(0, 6)}...{record.leaderAddress.slice(-4)}
          </div>
        </div>
      )
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
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
      title: '操作',
      key: 'action',
      width: isMobile ? 80 : 100,
      render: (_: any, record: CopyTrading) => (
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
      )
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
        
        <Table
          columns={columns}
          dataSource={copyTradings}
          rowKey="id"
          loading={loading}
          pagination={{
            pageSize: isMobile ? 10 : 20,
            showSizeChanger: !isMobile,
            showTotal: (total) => `共 ${total} 条`
          }}
          scroll={{ x: isMobile ? 800 : 'auto' }}
        />
      </Card>
    </div>
  )
}

export default CopyTradingList

