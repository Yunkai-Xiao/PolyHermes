import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { Card, Descriptions, Button, Space, Tag, Spin, message, Typography, Divider } from 'antd'
import { ArrowLeftOutlined, ReloadOutlined, EditOutlined } from '@ant-design/icons'
import { useAccountStore } from '../store/accountStore'
import type { Account } from '../types'
import { useMediaQuery } from 'react-responsive'

const { Title } = Typography

const AccountDetail: React.FC = () => {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const accountId = searchParams.get('id')
  
  const { fetchAccountDetail, fetchAccountBalance } = useAccountStore()
  const [account, setAccount] = useState<Account | null>(null)
  const [balance, setBalance] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [balanceLoading, setBalanceLoading] = useState(false)
  
  useEffect(() => {
    if (accountId) {
      loadAccountDetail()
      loadBalance()
    } else {
      message.error('账户ID不能为空')
      navigate('/accounts')
    }
  }, [accountId])
  
  const loadAccountDetail = async () => {
    if (!accountId) return
    
    setLoading(true)
    try {
      const accountData = await fetchAccountDetail(Number(accountId))
      setAccount(accountData)
    } catch (error: any) {
      message.error(error.message || '获取账户详情失败')
      navigate('/accounts')
    } finally {
      setLoading(false)
    }
  }
  
  const loadBalance = async () => {
    if (!accountId) return
    
    setBalanceLoading(true)
    try {
      const balanceData = await fetchAccountBalance(Number(accountId))
      setBalance(balanceData.balance || null)
    } catch (error: any) {
      console.error('获取余额失败:', error)
      // 余额查询失败不显示错误，只显示 "-"
      setBalance(null)
    } finally {
      setBalanceLoading(false)
    }
  }
  
  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '50px' }}>
        <Spin size="large" />
      </div>
    )
  }
  
  if (!account) {
    return null
  }
  
  return (
    <div style={{ 
      padding: isMobile ? '0' : undefined,
      margin: isMobile ? '0 -8px' : undefined
    }}>
      <div style={{ 
        display: 'flex', 
        justifyContent: 'space-between', 
        alignItems: 'center',
        marginBottom: isMobile ? '12px' : '16px',
        flexWrap: 'wrap',
        gap: '12px',
        padding: isMobile ? '0 8px' : '0'
      }}>
        <Space wrap>
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={() => navigate('/accounts')}
            size={isMobile ? 'middle' : 'large'}
          >
            返回
          </Button>
          <Title level={isMobile ? 4 : 2} style={{ margin: 0, fontSize: isMobile ? '16px' : undefined }}>
            {account.accountName || `账户 ${account.id}`}
          </Title>
        </Space>
        <Space wrap style={{ width: isMobile ? '100%' : 'auto' }}>
          <Button
            icon={<ReloadOutlined />}
            onClick={loadBalance}
            loading={balanceLoading}
            size={isMobile ? 'middle' : 'large'}
            block={isMobile}
            style={isMobile ? { minHeight: '44px' } : undefined}
          >
            刷新余额
          </Button>
          <Button
            type="primary"
            icon={<EditOutlined />}
            onClick={() => navigate(`/accounts/edit?id=${account.id}`)}
            size={isMobile ? 'middle' : 'large'}
            block={isMobile}
            style={isMobile ? { minHeight: '44px' } : undefined}
          >
            编辑
          </Button>
        </Space>
      </div>
      
      <Card style={{ 
        margin: isMobile ? '0 -8px' : '0',
        borderRadius: isMobile ? '0' : undefined
      }}>
        <Descriptions
          column={isMobile ? 1 : 2}
          bordered
          size={isMobile ? 'small' : 'middle'}
          style={{ fontSize: isMobile ? '14px' : undefined }}
        >
          <Descriptions.Item label="账户ID">
            {account.id}
          </Descriptions.Item>
          <Descriptions.Item label="账户名称">
            {account.accountName || '-'}
          </Descriptions.Item>
          <Descriptions.Item label="钱包地址" span={isMobile ? 1 : 2}>
            <span style={{ 
              fontFamily: 'monospace', 
              fontSize: isMobile ? '11px' : '14px',
              wordBreak: 'break-all',
              lineHeight: '1.4',
              display: 'block'
            }}>
              {account.walletAddress}
            </span>
          </Descriptions.Item>
          <Descriptions.Item label="默认账户">
            <Tag color={account.isDefault ? 'gold' : 'default'}>
              {account.isDefault ? '是' : '否'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="账户余额">
            {balanceLoading ? (
              <Spin size="small" />
            ) : balance ? (
              <span style={{ fontWeight: 'bold', color: '#1890ff' }}>
                {balance} USDC
              </span>
            ) : (
              <span style={{ color: '#999' }}>-</span>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      
      <Divider />
      
      <Card 
        title="API 凭证配置" 
        style={{ 
          marginTop: isMobile ? '12px' : '16px',
          margin: isMobile ? '0 -8px' : '0',
          borderRadius: isMobile ? '0' : undefined
        }}
      >
        <Descriptions
          column={isMobile ? 1 : 2}
          bordered
          size={isMobile ? 'small' : 'middle'}
          style={{ fontSize: isMobile ? '14px' : undefined }}
        >
          <Descriptions.Item label="API Key">
            <Tag color={account.apiKeyConfigured ? 'success' : 'default'}>
              {account.apiKeyConfigured ? '已配置' : '未配置'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="API Secret">
            <Tag color={account.apiSecretConfigured ? 'success' : 'default'}>
              {account.apiSecretConfigured ? '已配置' : '未配置'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="API Passphrase">
            <Tag color={account.apiPassphraseConfigured ? 'success' : 'default'}>
              {account.apiPassphraseConfigured ? '已配置' : '未配置'}
            </Tag>
          </Descriptions.Item>
          <Descriptions.Item label="配置状态">
            {account.apiKeyConfigured && account.apiSecretConfigured && account.apiPassphraseConfigured ? (
              <Tag color="success">完整配置</Tag>
            ) : (
              <Tag color="warning">部分配置</Tag>
            )}
          </Descriptions.Item>
        </Descriptions>
      </Card>
      
      {account.totalOrders !== undefined || account.totalPnl !== undefined ? (
        <>
          <Divider style={{ margin: isMobile ? '12px 0' : '16px 0' }} />
          <Card 
            title="交易统计" 
            style={{ 
              marginTop: isMobile ? '12px' : '16px',
              margin: isMobile ? '0 -8px' : '0',
              borderRadius: isMobile ? '0' : undefined
            }}
          >
            <Descriptions
              column={isMobile ? 1 : 2}
              bordered
              size={isMobile ? 'small' : 'middle'}
              style={{ fontSize: isMobile ? '14px' : undefined }}
            >
              {account.totalOrders !== undefined && (
                <Descriptions.Item label="总订单数">
                  {account.totalOrders}
                </Descriptions.Item>
              )}
              {account.totalPnl !== undefined && (
                <Descriptions.Item label="总盈亏">
                  <span style={{ 
                    fontWeight: 'bold',
                    color: account.totalPnl.startsWith('-') ? '#ff4d4f' : '#52c41a'
                  }}>
                    {account.totalPnl} USDC
                  </span>
                </Descriptions.Item>
              )}
            </Descriptions>
          </Card>
        </>
      ) : null}
    </div>
  )
}

export default AccountDetail

