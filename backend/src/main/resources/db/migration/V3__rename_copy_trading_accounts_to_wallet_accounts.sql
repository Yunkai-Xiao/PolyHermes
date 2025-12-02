-- ============================================
-- 重命名账户表：copy_trading_accounts -> wallet_accounts
-- ============================================

-- 检查表是否存在，如果存在则重命名
-- 注意：此迁移脚本假设表已经存在（从 V1 迁移创建）

-- 1. 查找外键约束名称并删除
SET @fk_name = NULL;
SELECT CONSTRAINT_NAME INTO @fk_name
FROM information_schema.KEY_COLUMN_USAGE 
WHERE TABLE_SCHEMA = DATABASE() 
  AND TABLE_NAME = 'copy_trading' 
  AND COLUMN_NAME = 'account_id' 
  AND REFERENCED_TABLE_NAME = 'copy_trading_accounts'
LIMIT 1;

-- 2. 如果找到外键，删除它
SET @drop_fk_sql = IF(@fk_name IS NOT NULL, 
    CONCAT('ALTER TABLE copy_trading DROP FOREIGN KEY `', @fk_name, '`'), 
    'SELECT 1');
PREPARE stmt FROM @drop_fk_sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- 3. 重命名表
RENAME TABLE copy_trading_accounts TO wallet_accounts;

-- 4. 重新添加外键约束
ALTER TABLE copy_trading 
ADD CONSTRAINT fk_copy_trading_account_id 
FOREIGN KEY (account_id) REFERENCES wallet_accounts(id) ON DELETE CASCADE;

-- 5. 更新表注释
ALTER TABLE wallet_accounts COMMENT = '钱包账户表';

