import { useEffect } from 'react'
import { useNavigate } from 'react-router-dom'
import { Card, Table, Button, Space, Tag, Popconfirm, message } from 'antd'
import { PlusOutlined, EditOutlined, DeleteOutlined } from '@ant-design/icons'
import { apiService } from '../services/api'
import { useState } from 'react'
import type { Leader } from '../types'
import { useMediaQuery } from 'react-responsive'

const LeaderList: React.FC = () => {
  const navigate = useNavigate()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [leaders, setLeaders] = useState<Leader[]>([])
  const [loading, setLoading] = useState(false)
  
  useEffect(() => {
    fetchLeaders()
  }, [])
  
  const fetchLeaders = async () => {
    setLoading(true)
    try {
      const response = await apiService.leaders.list()
      if (response.data.code === 0 && response.data.data) {
        setLeaders(response.data.data.list || [])
      } else {
        message.error(response.data.msg || '获取 Leader 列表失败')
      }
    } catch (error: any) {
      message.error(error.message || '获取 Leader 列表失败')
    } finally {
      setLoading(false)
    }
  }
  
  const handleDelete = async (leaderId: number) => {
    try {
      const response = await apiService.leaders.delete({ leaderId })
      if (response.data.code === 0) {
        message.success('删除 Leader 成功')
        fetchLeaders()
      } else {
        message.error(response.data.msg || '删除 Leader 失败')
      }
    } catch (error: any) {
      message.error(error.message || '删除 Leader 失败')
    }
  }
  
  const columns = [
    {
      title: 'Leader 名称',
      dataIndex: 'leaderName',
      key: 'leaderName',
      render: (text: string, record: Leader) => text || `Leader ${record.id}`
    },
    {
      title: '钱包地址',
      dataIndex: 'leaderAddress',
      key: 'leaderAddress',
      render: (address: string) => (
        <span style={{ fontFamily: 'monospace' }}>{address}</span>
      )
    },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      render: (category: string | undefined) => category ? (
        <Tag color={category === 'sports' ? 'blue' : 'green'}>{category}</Tag>
      ) : <Tag>全部</Tag>
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      render: (enabled: boolean) => (
        <Tag color={enabled ? 'success' : 'default'}>
          {enabled ? '启用' : '禁用'}
        </Tag>
      )
    },
    {
      title: '跟单比例',
      dataIndex: 'copyRatio',
      key: 'copyRatio',
      render: (ratio: string) => `${ratio}x`
    },
    {
      title: '操作',
      key: 'action',
      render: (_: any, record: Leader) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EditOutlined />}
            onClick={() => navigate(`/leaders/edit?id=${record.id}`)}
          >
            编辑
          </Button>
          <Popconfirm
            title="确定要删除这个 Leader 吗？"
            onConfirm={() => handleDelete(record.id)}
            okText="确定"
            cancelText="取消"
          >
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]
  
  return (
    <div>
      <div style={{ 
        display: 'flex', 
        justifyContent: 'space-between', 
        alignItems: 'center',
        marginBottom: '16px',
        flexWrap: 'wrap',
        gap: '12px'
      }}>
        <h2>Leader 管理</h2>
        <Button
          type="primary"
          icon={<PlusOutlined />}
          onClick={() => navigate('/leaders/add')}
          size={isMobile ? 'middle' : 'large'}
        >
          添加 Leader
        </Button>
      </div>
      
      <Card>
        <Table
          dataSource={leaders}
          columns={columns}
          rowKey="id"
          loading={loading}
          pagination={{
            pageSize: isMobile ? 10 : 20,
            showSizeChanger: !isMobile
          }}
        />
      </Card>
    </div>
  )
}

export default LeaderList

