-- Drop unused columns from backtest_task table
-- These fields are not needed for backtest scenarios as they use historical data

ALTER TABLE backtest_task DROP COLUMN IF EXISTS price_tolerance;
ALTER TABLE backtest_task DROP COLUMN IF EXISTS delay_seconds;
ALTER TABLE backtest_task DROP COLUMN IF EXISTS min_order_depth;
ALTER TABLE backtest_task DROP COLUMN IF EXISTS max_spread;
ALTER TABLE backtest_task DROP COLUMN IF EXISTS min_price;
ALTER TABLE backtest_task DROP COLUMN IF EXISTS max_price;
ALTER TABLE backtest_task DROP COLUMN IF EXISTS max_position_value;
ALTER TABLE backtest_task DROP COLUMN IF EXISTS max_market_end_date;
