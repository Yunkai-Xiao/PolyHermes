import { useCallback, useMemo, useState } from 'react'
import { Button, Card, Empty, Space, Switch, Table, Tag, Typography, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useTranslation } from 'react-i18next'
import { useMediaQuery } from 'react-responsive'
import { useWebSocketSubscription } from '../hooks/useWebSocket'
import type { OrderPushMessage, RealtimeNotificationPushMessage } from '../types'
import { formatNumber, getPolymarketUrl } from '../utils'

const { Text } = Typography

const MAX_ROWS = 500

interface RealtimeNotificationRow {
  key: string
  receivedAt: number
  channel: 'order' | 'notification'
  type: string
  level: 'success' | 'error' | 'warning' | 'info'
  accountName: string
  side?: string
  marketName: string
  marketId?: string
  marketUrl: string | null
  price?: string
  size?: string
  filled?: string
  status?: string
  orderId?: string
  source: string
  detail?: string
}

const normalizeTimestamp = (value: string | number | undefined): number => {
  if (value === undefined) {
    return Date.now()
  }
  const num = typeof value === 'string' ? Number(value) : value
  if (!Number.isFinite(num) || num <= 0) {
    return Date.now()
  }
  return num < 1_000_000_000_000 ? num * 1000 : num
}

const getTagColorByLevel = (level: string): string => {
  const normalized = level.toLowerCase()
  if (normalized === 'error') return 'error'
  if (normalized === 'warning') return 'warning'
  if (normalized === 'success') return 'success'
  return 'processing'
}

const RealtimeNotifications: React.FC = () => {
  const { t, i18n } = useTranslation()
  const isMobile = useMediaQuery({ maxWidth: 768 })
  const [paused, setPaused] = useState(false)
  const [rows, setRows] = useState<RealtimeNotificationRow[]>([])

  const appendRow = useCallback((row: RealtimeNotificationRow) => {
    setRows((prev) => [row, ...prev].slice(0, MAX_ROWS))
  }, [])

  const getOrderTypeText = useCallback((type: string): string => {
    switch (type) {
      case 'PLACEMENT':
        return t('order.create')
      case 'UPDATE':
        return t('order.update')
      case 'CANCELLATION':
        return t('order.cancel')
      default:
        return type
    }
  }, [t])

  const getNotificationTypeText = useCallback((eventType: string): string => {
    switch (eventType) {
      case 'ORDER_FAILURE':
        return t('realtimeNotifications.typeOrderFailure')
      case 'ORDER_FILTERED':
        return t('realtimeNotifications.typeOrderFiltered')
      default:
        return eventType
    }
  }, [t])

  const handleOrderPushMessage = useCallback((payload: OrderPushMessage) => {
    if (paused) {
      return
    }

    const detail = payload.orderDetail
    const order = payload.order
    const marketName = detail?.marketName || order.market
    const marketUrl = getPolymarketUrl(detail?.marketSlug, undefined, undefined, order.market)

    const source = payload.configName && payload.leaderName
      ? t('realtimeNotifications.sourceCopyTrading', { config: payload.configName, leader: payload.leaderName })
      : payload.configName
        ? t('realtimeNotifications.sourceCopyTradingNoLeader', { config: payload.configName })
        : payload.leaderName
          ? t('realtimeNotifications.sourceCopyTradingNoConfig', { leader: payload.leaderName })
          : t('realtimeNotifications.sourceManual')

    appendRow({
      key: `${order.id}-order-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      receivedAt: normalizeTimestamp(detail?.createdAt ?? order.timestamp ?? payload.timestamp),
      channel: 'order',
      type: getOrderTypeText(order.type),
      level: order.type === 'UPDATE' ? 'success' : order.type === 'CANCELLATION' ? 'warning' : 'info',
      accountName: payload.accountName,
      side: order.side,
      marketName,
      marketId: order.market,
      marketUrl,
      price: detail?.price ?? order.price,
      size: detail?.size ?? order.original_size,
      filled: detail?.filled ?? order.size_matched,
      status: detail?.status,
      orderId: order.id,
      source,
      detail: detail?.status ? `${t('order.status')}: ${detail.status}` : undefined,
    })
  }, [appendRow, getOrderTypeText, paused, t])

  const handleRealtimeNotification = useCallback((payload: RealtimeNotificationPushMessage) => {
    if (paused) {
      return
    }

    const marketId = payload.marketId
    const marketName = payload.marketTitle || marketId || '-'
    const marketUrl = getPolymarketUrl(payload.marketSlug, undefined, undefined, marketId)

    const detailParts: string[] = []
    if (payload.errorMessage) {
      detailParts.push(`${t('realtimeNotifications.detailError')}: ${payload.errorMessage}`)
    }
    if (payload.filterType) {
      detailParts.push(`${t('realtimeNotifications.detailFilterType')}: ${payload.filterType}`)
    }
    if (payload.filterReason) {
      detailParts.push(`${t('realtimeNotifications.detailFilterReason')}: ${payload.filterReason}`)
    }
    if (payload.amount) {
      detailParts.push(`${t('realtimeNotifications.detailAmount')}: ${payload.amount}`)
    }
    if (payload.bestOrderbookPrice) {
      detailParts.push(`${t('realtimeNotifications.detailBestOrderbookPrice')}: ${payload.bestOrderbookPrice}`)
    }
    if (payload.leaderTradePrice) {
      detailParts.push(`${t('realtimeNotifications.detailLeaderTradePrice')}: ${payload.leaderTradePrice}`)
    }
    if (payload.clobMinOrderSize) {
      detailParts.push(`${t('realtimeNotifications.detailClobMinOrderSize')}: ${payload.clobMinOrderSize}`)
    }
    if (payload.clobTickSize) {
      detailParts.push(`${t('realtimeNotifications.detailClobTickSize')}: ${payload.clobTickSize}`)
    }

    appendRow({
      key: `notification-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
      receivedAt: normalizeTimestamp(payload.timestamp),
      channel: 'notification',
      type: getNotificationTypeText(payload.eventType),
      level: (payload.level?.toLowerCase() as RealtimeNotificationRow['level']) || 'info',
      accountName: payload.accountName || t('realtimeNotifications.unknownAccount'),
      side: payload.side,
      marketName,
      marketId,
      marketUrl,
      price: payload.price,
      size: payload.size,
      status: payload.title,
      source: t('realtimeNotifications.sourceTelegramLike'),
      detail: detailParts.join(' | ') || undefined,
    })
  }, [appendRow, getNotificationTypeText, paused, t])

  const { connected: orderConnected } = useWebSocketSubscription<OrderPushMessage>('order', handleOrderPushMessage)
  const { connected: notificationConnected } = useWebSocketSubscription<RealtimeNotificationPushMessage>(
    'notification',
    handleRealtimeNotification
  )

  const handleClear = () => {
    setRows([])
    message.success(t('realtimeNotifications.clearSuccess'))
  }

  const columns: ColumnsType<RealtimeNotificationRow> = useMemo(() => [
    {
      title: t('realtimeNotifications.columnTime'),
      dataIndex: 'receivedAt',
      key: 'receivedAt',
      width: 180,
      render: (value: number) => new Date(value).toLocaleString(i18n.language || 'zh-CN'),
    },
    {
      title: t('realtimeNotifications.columnChannel'),
      dataIndex: 'channel',
      key: 'channel',
      width: 120,
      render: (value: RealtimeNotificationRow['channel']) => (
        <Tag color={value === 'order' ? 'blue' : 'gold'}>
          {value === 'order' ? t('realtimeNotifications.channelOrder') : t('realtimeNotifications.channelNotification')}
        </Tag>
      ),
    },
    {
      title: t('realtimeNotifications.columnType'),
      dataIndex: 'type',
      key: 'type',
      width: 130,
      render: (value: string, record: RealtimeNotificationRow) => (
        <Tag color={getTagColorByLevel(record.level)}>{value}</Tag>
      ),
    },
    {
      title: t('realtimeNotifications.columnAccount'),
      dataIndex: 'accountName',
      key: 'accountName',
      width: 140,
      ellipsis: true,
    },
    {
      title: t('realtimeNotifications.columnSide'),
      dataIndex: 'side',
      key: 'side',
      width: 90,
      render: (value?: string) => {
        if (!value) {
          return '-'
        }
        const normalized = value.toUpperCase()
        const color = normalized === 'BUY' ? 'green' : normalized === 'SELL' ? 'red' : 'default'
        const text = normalized === 'BUY' ? t('order.buy') : normalized === 'SELL' ? t('order.sell') : value
        return <Tag color={color}>{text}</Tag>
      },
    },
    {
      title: t('realtimeNotifications.columnMarket'),
      dataIndex: 'marketName',
      key: 'marketName',
      width: 280,
      render: (_: string, record: RealtimeNotificationRow) => (
        <Space direction="vertical" size={0}>
          {record.marketUrl ? (
            <a href={record.marketUrl} target="_blank" rel="noreferrer">
              {record.marketName}
            </a>
          ) : (
            <span>{record.marketName}</span>
          )}
          {record.marketId ? (
            <Text type="secondary" style={{ fontSize: 12 }}>{record.marketId}</Text>
          ) : null}
        </Space>
      ),
    },
    {
      title: t('realtimeNotifications.columnPrice'),
      dataIndex: 'price',
      key: 'price',
      width: 100,
      render: (value?: string) => (value ? formatNumber(value, 4) || '-' : '-'),
    },
    {
      title: t('realtimeNotifications.columnSize'),
      dataIndex: 'size',
      key: 'size',
      width: 100,
      render: (value?: string) => (value ? formatNumber(value, 4) || '-' : '-'),
    },
    {
      title: t('realtimeNotifications.columnFilled'),
      dataIndex: 'filled',
      key: 'filled',
      width: 100,
      render: (value?: string) => (value ? formatNumber(value, 4) || '-' : '-'),
    },
    {
      title: t('realtimeNotifications.columnStatus'),
      dataIndex: 'status',
      key: 'status',
      width: 160,
      render: (value?: string) => value || '-',
    },
    {
      title: t('realtimeNotifications.columnSource'),
      dataIndex: 'source',
      key: 'source',
      width: 200,
      render: (value: string) => value,
    },
    {
      title: t('realtimeNotifications.columnDetail'),
      dataIndex: 'detail',
      key: 'detail',
      width: 260,
      render: (value?: string) => value || '-',
    },
    {
      title: t('realtimeNotifications.columnOrderId'),
      dataIndex: 'orderId',
      key: 'orderId',
      width: 180,
      render: (value?: string) => (value ? <Text code>{value}</Text> : '-'),
    },
  ], [i18n.language, t])

  const connected = orderConnected && notificationConnected

  return (
    <div>
      <Card
        title={t('realtimeNotifications.title')}
        extra={
          <Space wrap>
            <Tag color={connected ? 'success' : 'error'}>
              {connected ? t('realtimeNotifications.connectionConnected') : t('realtimeNotifications.connectionDisconnected')}
            </Tag>
            <Space size={4}>
              <Text>{t('realtimeNotifications.pause')}</Text>
              <Switch checked={paused} onChange={setPaused} />
            </Space>
            <Button onClick={handleClear}>{t('realtimeNotifications.clear')}</Button>
          </Space>
        }
      >
        <div style={{ marginBottom: 12 }}>
          <Text type="secondary">{t('realtimeNotifications.subtitle')}</Text>
          <div>
            <Text type="secondary">{t('realtimeNotifications.count', { count: rows.length })}</Text>
          </div>
        </div>

        <Table<RealtimeNotificationRow>
          columns={columns}
          dataSource={rows}
          rowKey="key"
          size={isMobile ? 'small' : 'middle'}
          pagination={{
            pageSize: 20,
            showSizeChanger: !isMobile,
            pageSizeOptions: ['20', '50', '100'],
          }}
          locale={{
            emptyText: <Empty description={t('realtimeNotifications.empty')} />,
          }}
          scroll={{ x: 2200 }}
        />
      </Card>
    </div>
  )
}

export default RealtimeNotifications
