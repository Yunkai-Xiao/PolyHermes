-- ============================================
-- V32: 为 markets 表添加 resolved_at 字段
-- ============================================

ALTER TABLE markets
ADD COLUMN resolved_at BIGINT NULL COMMENT '市场结算时间（毫秒时间戳）' AFTER end_date;

