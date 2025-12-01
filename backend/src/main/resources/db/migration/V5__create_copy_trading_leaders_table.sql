-- 创建被跟单者（Leader）表
CREATE TABLE IF NOT EXISTS copy_trading_leaders (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    leader_address VARCHAR(42) NOT NULL UNIQUE COMMENT '被跟单者的钱包地址',
    leader_name VARCHAR(100) NULL COMMENT '被跟单者名称',
    category VARCHAR(20) NULL COMMENT '分类筛选（sports/crypto），null表示不筛选',
    created_at BIGINT NOT NULL COMMENT '创建时间（毫秒时间戳）',
    updated_at BIGINT NOT NULL COMMENT '更新时间（毫秒时间戳）',
    INDEX idx_leader_address (leader_address),
    INDEX idx_category (category)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='被跟单者表';

