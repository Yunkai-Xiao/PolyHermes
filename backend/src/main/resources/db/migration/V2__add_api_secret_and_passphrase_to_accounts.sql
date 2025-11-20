-- 添加 API Secret 和 Passphrase 字段
ALTER TABLE copy_trading_accounts 
ADD COLUMN api_secret VARCHAR(500) NULL COMMENT 'Polymarket API Secret（可选，加密存储）' AFTER api_key,
ADD COLUMN api_passphrase VARCHAR(500) NULL COMMENT 'Polymarket API Passphrase（可选，加密存储）' AFTER api_secret;

