package com.wrbug.polymarketbot.dto

/**
 * 实时通知推送消息（通过统一 WebSocket 的 notification 频道推送）
 */
data class RealtimeNotificationPushMessage(
    val eventType: String,  // ORDER_FAILURE / ORDER_FILTERED
    val level: String,  // error / warning / info / success
    val title: String,
    val marketTitle: String? = null,
    val marketId: String? = null,
    val marketSlug: String? = null,
    val side: String? = null,
    val outcome: String? = null,
    val price: String? = null,
    val size: String? = null,
    val amount: String? = null,
    val accountName: String? = null,
    val walletAddress: String? = null,
    val errorMessage: String? = null,
    val filterReason: String? = null,
    val filterType: String? = null,
    val leaderTradePrice: String? = null,
    val bestOrderbookPrice: String? = null,
    val clobMinOrderSize: String? = null,
    val clobTickSize: String? = null,
    val displayMessage: String? = null,  // Telegram 文本（HTML 格式）
    val timestamp: Long = System.currentTimeMillis()
)
