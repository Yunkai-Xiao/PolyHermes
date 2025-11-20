package com.wrbug.polymarketbot.util

import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * 日期工具类
 * 用于处理日期字符串和时间戳之间的转换
 */
object DateUtils {
    
    /**
     * ISO 8601 日期时间格式化器
     */
    private val isoFormatter = DateTimeFormatter.ISO_DATE_TIME
    
    /**
     * 将 ISO 8601 格式的日期字符串转换为时间戳（毫秒）
     * @param dateString ISO 8601 格式的日期字符串，如 "2020-11-04T00:00:00Z"
     * @return 时间戳（毫秒），如果转换失败返回 null
     */
    fun parseToTimestamp(dateString: String?): Long? {
        if (dateString.isNullOrBlank()) {
            return null
        }
        
        return try {
            // 尝试解析 ISO 8601 格式
            val instant = Instant.parse(dateString)
            instant.toEpochMilli()
        } catch (e: DateTimeParseException) {
            // 如果解析失败，尝试其他格式
            try {
                // 尝试使用 ISO_DATE_TIME 格式化器
                val dateTime = java.time.ZonedDateTime.parse(dateString, isoFormatter)
                dateTime.toInstant().toEpochMilli()
            } catch (e2: Exception) {
                // 所有解析都失败，返回 null
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * 将时间戳（毫秒）转换为 ISO 8601 格式的日期字符串
     * @param timestamp 时间戳（毫秒）
     * @return ISO 8601 格式的日期字符串
     */
    fun formatFromTimestamp(timestamp: Long?): String? {
        if (timestamp == null) {
            return null
        }
        
        return try {
            val instant = Instant.ofEpochMilli(timestamp)
            instant.toString()
        } catch (e: Exception) {
            null
        }
    }
}

