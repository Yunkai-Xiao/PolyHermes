-- 创建跟单模板表
CREATE TABLE IF NOT EXISTS copy_trading_templates (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    template_name VARCHAR(100) NOT NULL UNIQUE COMMENT '模板名称',
    copy_mode VARCHAR(10) NOT NULL DEFAULT 'RATIO' COMMENT '跟单金额模式（RATIO/FIXED）',
    copy_ratio DECIMAL(10, 2) NOT NULL DEFAULT 1.00 COMMENT '跟单比例（仅在copyMode=RATIO时生效）',
    fixed_amount DECIMAL(20, 8) NULL COMMENT '固定跟单金额（仅在copyMode=FIXED时生效）',
    max_order_size DECIMAL(20, 8) NOT NULL DEFAULT 1000.00000000 COMMENT '单笔订单最大金额（USDC）',
    min_order_size DECIMAL(20, 8) NOT NULL DEFAULT 1.00000000 COMMENT '单笔订单最小金额（USDC）',
    max_daily_loss DECIMAL(20, 8) NOT NULL DEFAULT 10000.00000000 COMMENT '每日最大亏损限制（USDC）',
    max_daily_orders INT NOT NULL DEFAULT 100 COMMENT '每日最大跟单订单数',
    price_tolerance DECIMAL(5, 2) NOT NULL DEFAULT 5.00 COMMENT '价格容忍度（百分比，0-100）',
    delay_seconds INT NOT NULL DEFAULT 0 COMMENT '跟单延迟（秒，默认0立即跟单）',
    poll_interval_seconds INT NOT NULL DEFAULT 5 COMMENT '轮询间隔（秒，仅在WebSocket不可用时使用）',
    use_websocket BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否优先使用WebSocket推送',
    websocket_reconnect_interval INT NOT NULL DEFAULT 5000 COMMENT 'WebSocket重连间隔（毫秒）',
    websocket_max_retries INT NOT NULL DEFAULT 10 COMMENT 'WebSocket最大重试次数',
    support_sell BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否支持跟单卖出',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_template_name (template_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟单模板表';

