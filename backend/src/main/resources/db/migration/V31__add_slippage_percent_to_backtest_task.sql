-- Add slippage percent for backtest execution price simulation
ALTER TABLE backtest_task
ADD COLUMN slippage_percent DECIMAL(5,2) NOT NULL DEFAULT 0.00 COMMENT '回测滑点百分比';
