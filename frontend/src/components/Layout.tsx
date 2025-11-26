import { useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { Layout as AntLayout, Menu, Drawer, Button } from 'antd'
import { useMediaQuery } from 'react-responsive'
import {
  WalletOutlined,
  UserOutlined,
  SettingOutlined,
  UnorderedListOutlined,
  BarChartOutlined,
  MenuOutlined
} from '@ant-design/icons'
import type { ReactNode } from 'react'

const { Header, Content, Sider } = AntLayout

interface LayoutProps {
  children: ReactNode
}

const Layout: React.FC<LayoutProps> = ({ children }) => {
  const navigate = useNavigate()
  const location = useLocation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [mobileMenuOpen, setMobileMenuOpen] = useState(false)
  
  const menuItems = [
    {
      key: '/accounts',
      icon: <WalletOutlined />,
      label: '账户管理'
    },
    {
      key: '/leaders',
      icon: <UserOutlined />,
      label: 'Leader 管理'
    },
    {
      key: '/config',
      icon: <SettingOutlined />,
      label: '跟单配置'
    },
    {
      key: '/positions',
      icon: <UnorderedListOutlined />,
      label: '仓位管理'
    },
    {
      key: '/statistics',
      icon: <BarChartOutlined />,
      label: '统计信息'
    }
  ]
  
  const handleMenuClick = (key: string) => {
    navigate(key)
    if (isMobile) {
      setMobileMenuOpen(false)
    }
  }
  
  if (isMobile) {
    // 移动端布局
    return (
      <AntLayout style={{ minHeight: '100vh' }}>
        <Header style={{ 
          background: '#001529', 
          padding: '0 16px',
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'space-between'
        }}>
          <div style={{ color: '#fff', fontSize: '18px', fontWeight: 'bold' }}>
            Polymarket 跟单
          </div>
          <Button
            type="text"
            icon={<MenuOutlined />}
            style={{ color: '#fff' }}
            onClick={() => setMobileMenuOpen(true)}
          />
        </Header>
        <Content style={{ 
          padding: '12px 8px', 
          background: '#f0f2f5',
          minHeight: 'calc(100vh - 64px)'
        }}>
          {children}
        </Content>
        <Drawer
          title="导航菜单"
          placement="left"
          onClose={() => setMobileMenuOpen(false)}
          open={mobileMenuOpen}
          bodyStyle={{ padding: 0 }}
        >
          <Menu
            mode="inline"
            selectedKeys={[location.pathname]}
            items={menuItems}
            onClick={({ key }) => handleMenuClick(key)}
            style={{ border: 'none' }}
          />
        </Drawer>
      </AntLayout>
    )
  }
  
  // 桌面端布局
  return (
    <AntLayout style={{ height: '100vh', overflow: 'hidden' }}>
      <Sider 
        width={200} 
        style={{ 
          background: '#001529',
          height: '100vh',
          position: 'fixed',
          left: 0,
          top: 0,
          overflow: 'hidden'
        }}
      >
        <div style={{ 
          height: '64px', 
          display: 'flex', 
          alignItems: 'center', 
          justifyContent: 'center',
          color: '#fff',
          fontSize: '18px',
          fontWeight: 'bold',
          flexShrink: 0
        }}>
          Polymarket 跟单
        </div>
        <Menu
          mode="inline"
          selectedKeys={[location.pathname]}
          items={menuItems}
          onClick={({ key }) => handleMenuClick(key)}
          style={{ 
            height: 'calc(100vh - 64px)', 
            borderRight: 0,
            overflowY: 'auto'
          }}
        />
      </Sider>
      <AntLayout style={{ marginLeft: 200, height: '100vh' }}>
        <Content style={{ 
          padding: '24px', 
          background: '#f0f2f5', 
          height: '100vh',
          overflowY: 'auto'
        }}>
          {children}
        </Content>
      </AntLayout>
    </AntLayout>
  )
}

export default Layout

