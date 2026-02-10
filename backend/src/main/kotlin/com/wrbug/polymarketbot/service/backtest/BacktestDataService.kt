package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.repository.LeaderRepository
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.delay
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service

/**
 * 回测数据服务
 * 直接从 Polymarket Data API 获取 Leader 历史交易
 */
@Service
class BacktestDataService(
    private val leaderRepository: LeaderRepository,
    private val retrofitFactory: RetrofitFactory,
    @Value("\${backtest.data-api.activity-max-offset:3000}")
    private val activityMaxOffset: Int
) {
    private val logger = LoggerFactory.getLogger(BacktestDataService::class.java)

    /**
     * 分页获取 Leader 历史交易（用于回测恢复）
     * 支持重试机制：最多重试5次，每次间隔1秒
     *
     * @param leaderId Leader ID
     * @param startTime 开始时间（毫秒时间戳）
     * @param endTime 结束时间（毫秒时间戳）
     * @param page 页码 (从 0 开始)
     * @param size 每页数量
     * @return 历史交易列表
     * @throws Exception 重试5次后仍然失败时抛出异常
     */
    suspend fun getLeaderHistoricalTradesForPage(
        leaderId: Long,
        startTime: Long,
        endTime: Long,
        page: Int,
        size: Int
    ): List<TradeData> {
        logger.info("分页获取 Leader 历史交易: leaderId=$leaderId, timeRange=$startTime - $endTime, page=$page, size=$size")

        // 1. 验证 Leader 是否存在
        val leader = leaderRepository.findById(leaderId).orElse(null)
            ?: throw IllegalArgumentException("Leader 不存在: $leaderId")

        val dataApi = retrofitFactory.createDataApi()
        val offset = page * size
        val maxRetries = 5
        val retryDelay = 1000L  // 1秒

        // Data API 的活动查询在高 offset 下会返回 400，提前结束分页避免任务失败
        if (offset > activityMaxOffset) {
            logger.info("第 $page 页 offset=$offset 超过配置上限 $activityMaxOffset，视为无更多数据并结束分页")
            return emptyList()
        }

        // 2. 重试机制：最多重试5次
        var lastException: Exception? = null
        for (attempt in 1..maxRetries) {
            try {
                val response = dataApi.getUserActivity(
                    user = leader.leaderAddress,
                    type = listOf("TRADE"),
                    start = startTime / 1000,
                    end = endTime / 1000,
                    limit = size,
                    offset = offset,
                    sortBy = "timestamp",
                    sortDirection = "asc"
                )

                if (!response.isSuccessful || response.body() == null) {
                    val code = response.code()
                    val responseMessage = response.message().orEmpty()
                    val errorBody = try {
                        response.errorBody()?.string()?.take(500).orEmpty()
                    } catch (e: Exception) {
                        ""
                    }

                    // 页码大于 0 时返回 400，通常是分页越界/offset 超限，作为结束条件处理
                    if (code == 400 && page > 0) {
                        logger.warn(
                            "第 $page 页请求返回 400，视为分页结束: offset=$offset, size=$size, message=$responseMessage, errorBody=$errorBody"
                        )
                        return emptyList()
                    }

                    val errorMessage = "从 Data API 获取用户活动失败: code=$code, message=$responseMessage, errorBody=$errorBody"
                    if (code in 400..499 && code != 429) {
                        throw NonRetryableDataApiException(errorMessage)
                    }
                    throw Exception(errorMessage)
                }

                val activities = response.body()!!
                logger.info("成功获取第 $page 页数据，共 ${activities.size} 条交易（第 $attempt 次尝试）")

                return activities.mapNotNull { activity ->
                    try {
                        if (activity.type != "TRADE") {
                            return@mapNotNull null
                        }

                        if (activity.side == null || activity.price == null || activity.size == null || activity.usdcSize == null) {
                            logger.warn("活动数据缺少必要字段，跳过: activity=$activity")
                            return@mapNotNull null
                        }

                        val tradeTimestamp = activity.timestamp * 1000
                        if (tradeTimestamp < startTime || tradeTimestamp > endTime) {
                            logger.debug("交易时间超出范围，跳过: timestamp=$tradeTimestamp, range=$startTime - $endTime")
                            return@mapNotNull null
                        }

                        TradeData(
                            tradeId = activity.transactionHash ?: "${activity.timestamp}_${activity.conditionId}_${activity.side}",
                            marketId = activity.conditionId,
                            marketTitle = activity.title,
                            marketSlug = activity.slug,
                            side = activity.side.uppercase(),
                            outcome = activity.outcome ?: activity.outcomeIndex?.toString() ?: "",
                            outcomeIndex = activity.outcomeIndex,
                            price = activity.price.toSafeBigDecimal(),
                            size = activity.size.toSafeBigDecimal(),
                            amount = activity.usdcSize.toSafeBigDecimal(),
                            timestamp = tradeTimestamp
                        )
                    } catch (e: Exception) {
                        logger.warn("转换活动数据失败: activity=$activity, error=${e.message}", e)
                        null
                    }
                }

            } catch (e: Exception) {
                if (e is NonRetryableDataApiException) {
                    logger.error("获取第 $page 页数据出现不可重试错误: ${e.message}")
                    throw e
                }
                lastException = e
                logger.warn("第 $attempt/$maxRetries 次尝试获取第 $page 页数据失败: ${e.message}")

                // 如果不是最后一次尝试，则等待后重试
                if (attempt < maxRetries) {
                    logger.info("等待 $retryDelay 毫秒后重试...")
                    delay(retryDelay)
                }
            }
        }

        // 重试5次后仍然失败，抛出异常
        val errorMsg = "重试 $maxRetries 次后仍然失败获取第 $page 页数据"
        logger.error(errorMsg, lastException)
        throw Exception(errorMsg, lastException)
    }

    private class NonRetryableDataApiException(message: String) : Exception(message)
}
