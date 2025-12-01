-- 创建跟单关系表（钱包-模板关联，多对多关系）
CREATE TABLE IF NOT EXISTS copy_trading (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    account_id BIGINT NOT NULL COMMENT '钱包账户ID',
    template_id BIGINT NOT NULL COMMENT '模板ID',
    leader_id BIGINT NOT NULL COMMENT 'Leader ID',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '是否启用',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    UNIQUE KEY uk_account_template_leader (account_id, template_id, leader_id),
    INDEX idx_account_id (account_id),
    INDEX idx_template_id (template_id),
    INDEX idx_leader_id (leader_id),
    INDEX idx_enabled (enabled),
    FOREIGN KEY (account_id) REFERENCES copy_trading_accounts(id) ON DELETE CASCADE,
    FOREIGN KEY (template_id) REFERENCES copy_trading_templates(id) ON DELETE RESTRICT,
    FOREIGN KEY (leader_id) REFERENCES copy_trading_leaders(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='跟单关系表（钱包-模板关联）';

