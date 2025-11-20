-- 创建账户表
CREATE TABLE IF NOT EXISTS copy_trading_accounts (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    private_key VARCHAR(500) NOT NULL COMMENT '私钥（加密存储）',
    wallet_address VARCHAR(42) NOT NULL UNIQUE COMMENT '钱包地址（从私钥推导）',
    api_key VARCHAR(500) NULL COMMENT 'Polymarket API Key（可选，加密存储）',
    account_name VARCHAR(100) NULL COMMENT '账户名称',
    is_default BOOLEAN NOT NULL DEFAULT FALSE COMMENT '是否默认账户',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_wallet_address (wallet_address),
    INDEX idx_is_default (is_default)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟单系统账户表';

