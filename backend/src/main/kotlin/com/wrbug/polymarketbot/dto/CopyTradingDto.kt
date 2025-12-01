package com.wrbug.polymarketbot.dto

/**
 * 跟单创建请求
 */
data class CopyTradingCreateRequest(
    val accountId: Long,
    val templateId: Long,
    val leaderId: Long,
    val enabled: Boolean = true
)

/**
 * 跟单列表请求
 */
data class CopyTradingListRequest(
    val accountId: Long? = null,
    val templateId: Long? = null,
    val leaderId: Long? = null,
    val enabled: Boolean? = null
)

/**
 * 跟单状态更新请求
 */
data class CopyTradingUpdateStatusRequest(
    val copyTradingId: Long,
    val enabled: Boolean
)

/**
 * 跟单删除请求
 */
data class CopyTradingDeleteRequest(
    val copyTradingId: Long
)

/**
 * 查询钱包绑定的模板请求
 */
data class AccountTemplatesRequest(
    val accountId: Long
)

/**
 * 跟单信息响应
 */
data class CopyTradingDto(
    val id: Long,
    val accountId: Long,
    val accountName: String?,
    val walletAddress: String,
    val templateId: Long,
    val templateName: String,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * 跟单列表响应
 */
data class CopyTradingListResponse(
    val list: List<CopyTradingDto>,
    val total: Long
)

/**
 * 钱包绑定的模板信息
 */
data class AccountTemplateDto(
    val templateId: Long,
    val templateName: String,
    val copyTradingId: Long,
    val leaderId: Long,
    val leaderName: String?,
    val leaderAddress: String,
    val enabled: Boolean
)

/**
 * 钱包绑定的模板列表响应
 */
data class AccountTemplatesResponse(
    val list: List<AccountTemplateDto>,
    val total: Long
)

