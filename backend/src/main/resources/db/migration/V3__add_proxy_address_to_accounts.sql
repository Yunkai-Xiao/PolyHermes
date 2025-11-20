-- 添加代理地址字段到账户表
ALTER TABLE copy_trading_accounts 
ADD COLUMN proxy_address VARCHAR(42) NOT NULL COMMENT 'Polymarket 代理钱包地址（从合约获取，必须）' AFTER wallet_address;

