package com.wrbug.polymarketbot.service.backtest

import com.wrbug.polymarketbot.dto.TradeData
import com.wrbug.polymarketbot.dto.BacktestStatisticsDto
import com.wrbug.polymarketbot.entity.BacktestTask
import com.wrbug.polymarketbot.entity.BacktestTrade
import com.wrbug.polymarketbot.entity.CopyTrading
import com.wrbug.polymarketbot.repository.BacktestTradeRepository
import com.wrbug.polymarketbot.repository.BacktestTaskRepository
import com.wrbug.polymarketbot.service.common.MarketService
import com.wrbug.polymarketbot.service.common.MarketPriceService
import com.wrbug.polymarketbot.service.copytrading.configs.CopyTradingFilterService
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.SimpleDateFormat
import java.util.*

@Service
class BacktestExecutionService(
    private val backtestTaskRepository: BacktestTaskRepository,
    private val backtestTradeRepository: BacktestTradeRepository,
    private val backtestDataService: BacktestDataService,
    private val marketPriceService: MarketPriceService,
    private val marketService: MarketService,
    private val copyTradingFilterService: CopyTradingFilterService
) {
    private val logger = LoggerFactory.getLogger(BacktestExecutionService::class.java)

    /**
     * 持仓数据结构
     */
    data class Position(
        val marketId: String,
        val outcome: String,
        var outcomeIndex: Int?,
        var quantity: BigDecimal,
        var avgPrice: BigDecimal,
        var leaderOpenQuantity: BigDecimal?
    )

    private val hundred = BigDecimal("100")
    private val minBacktestPrice = BigDecimal("0.00000001")

    private fun normalizeOutcome(outcome: String?): String {
        return outcome?.trim()?.lowercase().orEmpty()
    }

    private fun positionKey(marketId: String, outcome: String?, outcomeIndex: Int?): String {
        val outcomeKey = normalizeOutcome(outcome).ifBlank { outcomeIndex?.toString().orEmpty() }.ifBlank { "0" }
        return "$marketId:$outcomeKey"
    }

    private suspend fun resolveOutcomeIndex(marketId: String, outcome: String?, outcomeIndex: Int?): Int? {
        if (outcomeIndex != null) return outcomeIndex
        val normalized = normalizeOutcome(outcome)
        normalized.toIntOrNull()?.let { return it }
        return marketService.getOutcomeIndex(marketId, outcome)
    }

    /**
     * 将回测任务转换为虚拟的 CopyTrading 配置用于执行
     * 注意：回测场景使用历史数据，不需要实时跟单的相关配置
     */
    private fun taskToCopyTrading(task: BacktestTask): CopyTrading {
        return CopyTrading(
            id = task.id,
            accountId = 0L,
            leaderId = task.leaderId,
            enabled = true,
            copyMode = task.copyMode,
            copyRatio = task.copyRatio,
            fixedAmount = null,
            maxOrderSize = task.maxOrderSize,
            minOrderSize = task.minOrderSize,
            maxDailyLoss = task.maxDailyLoss,
            maxDailyOrders = task.maxDailyOrders,
            priceTolerance = BigDecimal.ZERO,  // 回测使用历史价格，不需要容忍度
            delaySeconds = 0,  // 回测按时间线执行，无需延迟
            pollIntervalSeconds = 5,
            useWebSocket = false,
            websocketReconnectInterval = 5000,
            websocketMaxRetries = 10,
            supportSell = task.supportSell,
            minOrderDepth = null,  // 回测无实时订单簿数据
            maxSpread = null,  // 回测无实时价差数据
            keywordFilterMode = task.keywordFilterMode,
            keywords = task.keywords,
            configName = null,
            pushFailedOrders = false,
            pushFilteredOrders = false,
            createdAt = task.createdAt,
            updatedAt = task.updatedAt
        )
    }

    /**
     * 执行回测任务（支持分页和恢复）
     * 自动处理所有页面的数据，支持中断恢复
     */
    @Transactional
    suspend fun executeBacktest(task: BacktestTask, page: Int = 0, size: Int = 100) {
        try {
            logger.info("开始执行回测任务: taskId=${task.id}, taskName=${task.taskName}, startPage=$page, pageSize=$size")

            // 1. 更新任务状态为 RUNNING
            task.status = "RUNNING"
            task.executionStartedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)

            // 2. 初始化
            var currentBalance = task.initialBalance
            val positions = mutableMapOf<String, Position>()
            val trades = mutableListOf<BacktestTrade>()
            // 每日订单数缓存：key为日期字符串(yyyy-MM-dd)，value为当天的 BUY 订单数
            val dailyOrderCountCache = mutableMapOf<String, Int>()
            // 每日亏损缓存：key为日期字符串(yyyy-MM-dd)，value为当天的累计亏损金额
            val dailyLossCache = mutableMapOf<String, BigDecimal>()

            // 3. 计算回测时间范围
            val endTime = System.currentTimeMillis()
            val startTime = task.startTime

            logger.info("回测时间范围: ${formatTimestamp(startTime)} - ${formatTimestamp(endTime)}, " +
                "初始余额: ${task.initialBalance.toPlainString()}")

            // 4. 恢复机制：如果有恢复点，计算从哪一页开始（页码从 0 开始）
            val startPage = if (task.lastProcessedTradeIndex != null) {
                val lastProcessedIndex = task.lastProcessedTradeIndex!!
                // 计算已处理的页码（从 0 开始）
                val processedPage = lastProcessedIndex / size

                // 特殊情况：如果lastProcessedTradeIndex刚好是100的倍数减1（比如99,199,299...）
                // 说明该页已经完全处理，应该从下一页开始
                val nextPage = if (lastProcessedIndex % size == size - 1) {
                    processedPage + 1
                } else {
                    processedPage
                }

                logger.info("恢复任务：已处理索引=$lastProcessedIndex, 计算页码=$nextPage, size=$size")
                nextPage
            } else {
                logger.info("新任务：从第0页开始")
                0
            }

            // 5. 分页获取和处理交易数据
            var currentPage = maxOf(startPage, page)
            // 计算下一个要处理的全局索引（用于日志和统计）
            val nextGlobalIndex = if (task.lastProcessedTradeIndex != null) {
                task.lastProcessedTradeIndex!! + 1
            } else {
                0
            }

            logger.info("开始分页处理：起始页=$currentPage, 下一个要处理的索引=$nextGlobalIndex")

            while (true) {
                // 定期从数据库重新加载任务状态，确保能及时响应停止操作
                val currentTaskStatus = backtestTaskRepository.findById(task.id!!).orElse(null)
                if (currentTaskStatus == null || currentTaskStatus.status != "RUNNING") {
                    logger.info("回测任务状态已变更: ${currentTaskStatus?.status}，停止执行")
                    break
                }

                logger.info("正在获取第 $currentPage 页数据...")

                // 每页使用独立的交易列表，避免跨页重复保存
                val currentPageTrades = mutableListOf<BacktestTrade>()

                try {
                    // 获取当前页的交易数据（支持重试5次）
                    val pageTrades = backtestDataService.getLeaderHistoricalTradesForPage(
                        task.leaderId,
                        startTime,
                        endTime,
                        currentPage,
                        size
                    )

                    if (pageTrades.isEmpty()) {
                        logger.info("第 $currentPage 页无数据，所有数据处理完成")
                        break
                    }

                    logger.info("第 $currentPage 页获取到 ${pageTrades.size} 条交易")

                    // 处理当前页的交易
                    var lastProcessedIndexInPage: Int? = null
                    for (localIndex in pageTrades.indices) {
                        val leaderTrade = pageTrades[localIndex]
                        // 计算当前交易在全局数据中的索引（从 0 开始）
                        val index = currentPage * size + localIndex

                        // 如果是恢复任务，跳过已处理的条目
                        if (task.lastProcessedTradeIndex != null && index <= task.lastProcessedTradeIndex!!) {
                            logger.debug("跳过已处理的交易: index=$index, lastProcessedIndex=${task.lastProcessedTradeIndex}")
                            continue
                        }

                        // 记录当前处理的索引
                        lastProcessedIndexInPage = index

                        // 更新进度
                        val progress = if (pageTrades.size > 0) {
                            (localIndex * 100) / pageTrades.size
                        } else {
                            0
                        }
                        if (progress > task.progress) {
                            task.progress = progress
                            task.processedTradeCount = index + 1
                            backtestTaskRepository.save(task)
                        }

                        try {
                            // 5.1 在历史时间线上按 resolved_at 结算到当前时点
                            currentBalance = settleResolvedPositionsUpTo(
                                task = task,
                                positions = positions,
                                currentBalance = currentBalance,
                                settlementTrades = currentPageTrades,
                                upToTime = leaderTrade.timestamp
                            )

                            // 5.2 检查余额和持仓状态
                            if (currentBalance < BigDecimal.ZERO) {
                                logger.info("余额已为负，直接终止回测: $currentBalance")
                                break
                            }
                            if (currentBalance < BigDecimal.ONE && positions.isEmpty()) {
                                logger.info("余额不足且无持仓，停止回测: $currentBalance")
                                break
                            }

                            // 5.3 应用过滤规则
                            val resolvedOutcomeIndex = resolveOutcomeIndex(
                                marketId = leaderTrade.marketId,
                                outcome = leaderTrade.outcome,
                                outcomeIndex = leaderTrade.outcomeIndex
                            )
                            val copyTrading = taskToCopyTrading(task)
                            val filterResult = copyTradingFilterService.checkFilters(
                                copyTrading,
                                tokenId = "",
                                tradePrice = leaderTrade.price,
                                copyOrderAmount = null,
                                marketId = leaderTrade.marketId,
                                marketTitle = leaderTrade.marketTitle,
                                marketEndDate = null,
                                outcomeIndex = resolvedOutcomeIndex,
                                skipOrderbookValidation = true
                            )

                            if (!filterResult.isPassed) {
                                logger.debug(
                                    "交易被过滤: tradeId=${leaderTrade.tradeId}, status=${filterResult.status}, reason=${filterResult.reason}"
                                )
                                continue
                            }

                            // 5.4 交易日期缓存（用于每日风控）
                            val tradeDate = formatDate(leaderTrade.timestamp)
                            val dailyOrderCount = dailyOrderCountCache.getOrDefault(tradeDate, 0)

                            // 5.6 处理买卖逻辑
                            val leaderPrice = leaderTrade.price.toSafeBigDecimal()
                            if (leaderTrade.side == "BUY") {
                                // 每日订单数限制仅限制 BUY
                                if (dailyOrderCount >= task.maxDailyOrders) {
                                    logger.info("已达到每日最大 BUY 订单数限制: $dailyOrderCount / ${task.maxDailyOrders}")
                                    continue
                                }

                                // 计算跟单金额
                                val followAmount = calculateFollowAmount(task, leaderTrade)
                                val finalFollowAmount = if (followAmount > task.maxOrderSize) {
                                    logger.info("跟单金额超过最大限制: $followAmount > ${task.maxOrderSize}，调整为最大值")
                                    task.maxOrderSize
                                } else if (followAmount < task.minOrderSize) {
                                    logger.info("跟单金额低于最小限制: $followAmount < ${task.minOrderSize}，调整为最小值")
                                    task.minOrderSize
                                } else {
                                    followAmount
                                }

                                // 每日最大亏损限制仅限制 BUY
                                val dailyLoss = dailyLossCache.getOrDefault(tradeDate, BigDecimal.ZERO)
                                if (dailyLoss > task.maxDailyLoss) {
                                    logger.info("已达到每日最大亏损限制: $dailyLoss / ${task.maxDailyLoss}，跳过买入订单")
                                    continue
                                }

                                val executedPrice = applyBuySlippage(leaderPrice, task.slippagePercent)
                                var actualFollowAmount = finalFollowAmount
                                if (actualFollowAmount > currentBalance) {
                                    logger.info("余额不足以下单目标金额: balance=$currentBalance, amount=$actualFollowAmount，按余额调整")
                                    actualFollowAmount = currentBalance
                                }
                                if (actualFollowAmount < task.minOrderSize) {
                                    logger.info("可用余额低于最小下单金额: available=$actualFollowAmount < min=${task.minOrderSize}，跳过买入")
                                    continue
                                }
                                if (actualFollowAmount <= BigDecimal.ZERO) {
                                    continue
                                }
                                // 买入逻辑
                                val quantity = actualFollowAmount.divide(executedPrice, 8, java.math.RoundingMode.DOWN)
                                if (quantity <= BigDecimal.ZERO) {
                                    continue
                                }
                                val totalCost = actualFollowAmount

                                // 更新余额和持仓
                                currentBalance -= totalCost
                                val positionKey = positionKey(leaderTrade.marketId, leaderTrade.outcome, resolvedOutcomeIndex)
                                val leaderBuyQuantity = leaderTrade.size.toSafeBigDecimal()
                                val existingPosition = positions[positionKey]
                                if (existingPosition != null) {
                                    val oldQuantity = existingPosition.quantity
                                    val newQuantity = oldQuantity.add(quantity)
                                    if (newQuantity > BigDecimal.ZERO) {
                                        val weightedCost = oldQuantity.multiply(existingPosition.avgPrice)
                                            .add(quantity.multiply(executedPrice))
                                        existingPosition.avgPrice = weightedCost.divide(newQuantity, 8, RoundingMode.HALF_UP)
                                    }
                                    existingPosition.quantity = newQuantity
                                    existingPosition.leaderOpenQuantity = (existingPosition.leaderOpenQuantity
                                        ?: BigDecimal.ZERO).add(leaderBuyQuantity)
                                } else {
                                    positions[positionKey] = Position(
                                        marketId = leaderTrade.marketId,
                                        outcome = leaderTrade.outcome ?: "",
                                        outcomeIndex = resolvedOutcomeIndex,
                                        quantity = quantity,
                                        avgPrice = executedPrice,
                                        leaderOpenQuantity = leaderBuyQuantity
                                    )
                                }

                                // 记录交易到当前页列表
                                currentPageTrades.add(BacktestTrade(
                                    backtestTaskId = task.id!!,
                                    tradeTime = leaderTrade.timestamp,
                                    marketId = leaderTrade.marketId,
                                    marketTitle = leaderTrade.marketTitle,
                                    side = "BUY",
                                    outcome = leaderTrade.outcome ?: leaderTrade.outcomeIndex.toString(),
                                    outcomeIndex = resolvedOutcomeIndex,
                                    quantity = quantity,
                                    price = executedPrice,
                                    amount = actualFollowAmount,
                                    fee = BigDecimal.ZERO,
                                    profitLoss = null,
                                    balanceAfter = currentBalance,
                                    leaderTradeId = leaderTrade.tradeId
                                ))

                                // 更新每日订单数缓存
                                dailyOrderCountCache[tradeDate] = dailyOrderCount + 1

                            } else {
                                // SELL 逻辑
                                if (!task.supportSell) {
                                    continue
                                }
                                val executedPrice = applySellSlippage(leaderPrice, task.slippagePercent)

                                val positionKey = positionKey(leaderTrade.marketId, leaderTrade.outcome, resolvedOutcomeIndex)
                                val position = positions[positionKey] ?: continue

                                // 计算卖出数量
                                val sellQuantity = if (task.copyMode == "RATIO") {
                                    val leaderOpenQuantity = position.leaderOpenQuantity ?: BigDecimal.ZERO
                                    if (leaderOpenQuantity > BigDecimal.ZERO) {
                                        position.quantity.multiply(
                                            leaderTrade.size.divide(leaderOpenQuantity, 8, java.math.RoundingMode.DOWN)
                                        )
                                    } else {
                                        position.quantity
                                    }
                                } else {
                                    position.quantity
                                }

                                val actualSellQuantity = if (sellQuantity > position.quantity) {
                                    position.quantity
                                } else {
                                    sellQuantity
                                }
                                if (actualSellQuantity <= BigDecimal.ZERO) {
                                    continue
                                }

                                // 计算卖出金额
                                val sellAmount = actualSellQuantity.multiply(executedPrice)

                                // 检查卖出金额限制：低于最小金额时跳过，超过最大金额时按最大金额折算数量
                                if (sellAmount < task.minOrderSize) {
                                    logger.info("卖出金额低于最小限制: $sellAmount < ${task.minOrderSize}，跳过卖出")
                                    continue
                                }
                                val finalSellQuantity = if (sellAmount > task.maxOrderSize) {
                                    logger.info("卖出金额超过最大限制: $sellAmount > ${task.maxOrderSize}，按最大金额折算卖出数量")
                                    task.maxOrderSize.divide(executedPrice, 8, RoundingMode.DOWN)
                                } else {
                                    actualSellQuantity
                                }
                                if (finalSellQuantity <= BigDecimal.ZERO) {
                                    continue
                                }
                                val finalSellAmount = finalSellQuantity.multiply(executedPrice)

                                val netAmount = finalSellAmount

                                // 计算盈亏
                                val cost = finalSellQuantity.multiply(position.avgPrice)
                                val profitLoss = netAmount.subtract(cost)

                                // 更新余额和持仓
                                currentBalance += netAmount
                                position.quantity = position.quantity.subtract(finalSellQuantity)

                                if (task.copyMode == "RATIO" && position.leaderOpenQuantity != null) {
                                    val leaderOpen = position.leaderOpenQuantity ?: BigDecimal.ZERO
                                    val leaderSell = leaderTrade.size.toSafeBigDecimal()
                                    val leaderReduction = if (leaderSell > leaderOpen) leaderOpen else leaderSell
                                    position.leaderOpenQuantity = leaderOpen.subtract(leaderReduction)
                                }

                                if (position.quantity <= BigDecimal.ZERO) {
                                    positions.remove(positionKey)
                                }

                                // 记录交易到当前页列表
                                currentPageTrades.add(BacktestTrade(
                                    backtestTaskId = task.id!!,
                                    tradeTime = leaderTrade.timestamp,
                                    marketId = leaderTrade.marketId,
                                    marketTitle = leaderTrade.marketTitle,
                                    side = "SELL",
                                    outcome = leaderTrade.outcome ?: leaderTrade.outcomeIndex.toString(),
                                    outcomeIndex = resolvedOutcomeIndex,
                                    quantity = finalSellQuantity,
                                    price = executedPrice,
                                    amount = finalSellAmount,
                                    fee = BigDecimal.ZERO,
                                    profitLoss = profitLoss,
                                    balanceAfter = currentBalance,
                                    leaderTradeId = leaderTrade.tradeId
                                ))
                                // SELL 订单不计入每日订单数限制
                                
                                // 更新每日亏损缓存（只累加亏损，不累加盈利）
                                if (profitLoss < BigDecimal.ZERO) {
                                    val currentDailyLoss = dailyLossCache.getOrDefault(tradeDate, BigDecimal.ZERO)
                                    dailyLossCache[tradeDate] = currentDailyLoss + profitLoss.negate()
                                }
                            }

                        } catch (e: Exception) {
                            logger.error("处理交易失败: tradeId=${leaderTrade.tradeId}", e)
                        }
                    }

                    // 保存当前页的所有交易（每页处理完成后保存，避免重复插入）
                    if (currentPageTrades.isNotEmpty()) {
                        logger.info("保存第 $currentPage 页的交易数据，共 ${currentPageTrades.size} 笔")
                        
                        // 批量保存当前页的交易
                        backtestTradeRepository.saveAll(currentPageTrades)

                        // 更新当前页的最后处理信息
                        val lastTradeInPage = currentPageTrades.lastOrNull()
                        if (lastTradeInPage != null && lastProcessedIndexInPage != null) {
                            task.lastProcessedTradeTime = lastTradeInPage.tradeTime
                            task.lastProcessedTradeIndex = lastProcessedIndexInPage
                            task.processedTradeCount = lastProcessedIndexInPage + 1
                            task.finalBalance = currentBalance
                            backtestTaskRepository.save(task)

                            logger.info("第 $currentPage 页处理完成，更新索引: ${task.lastProcessedTradeIndex}, 总处理数: ${task.processedTradeCount}")
                        }
                    } else {
                        logger.info("第 $currentPage 页没有交易需要保存")
                    }

                    // 将当前页交易添加到全局列表（用于最终统计）
                    trades.addAll(currentPageTrades)

                    // 准备处理下一页
                    currentPage++

                } catch (e: Exception) {
                    logger.error("获取或处理第 $currentPage 页数据失败: ${e.message}", e)
                    // 重试失败，标记任务为 FAILED
                    throw e
                }
            }

            // 6. 结算到回测结束时点（只结算 resolved_at <= endTime 的仓位）
            val endResolvedTrades = mutableListOf<BacktestTrade>()
            currentBalance = settleResolvedPositionsUpTo(
                task = task,
                positions = positions,
                currentBalance = currentBalance,
                settlementTrades = endResolvedTrades,
                upToTime = endTime
            )
            if (endResolvedTrades.isNotEmpty()) {
                backtestTradeRepository.saveAll(endResolvedTrades)
                trades.addAll(endResolvedTrades)
            }

            // 7. 回测区间内仍未结算的仓位：按回测结束时点的市场价做估值（包含未实现盈亏）
            val markTrades = mutableListOf<BacktestTrade>()
            currentBalance = markToMarketRemainingPositionsAtBacktestEnd(
                task = task,
                positions = positions,
                currentBalance = currentBalance,
                settlementTrades = markTrades,
                currentTime = endTime
            )
            if (markTrades.isNotEmpty()) {
                backtestTradeRepository.saveAll(markTrades)
                trades.addAll(markTrades)
            }

            // 8. 计算最终统计数据
            val statistics = calculateStatistics(trades)

            // 9. 更新任务状态
            val profitAmount = currentBalance.subtract(task.initialBalance)
            val profitRate = if (task.initialBalance > BigDecimal.ZERO) {
                profitAmount.divide(task.initialBalance, 4, java.math.RoundingMode.HALF_UP).multiply(BigDecimal("100"))
            } else {
                BigDecimal.ZERO
            }
            val finalStatus = if (task.status == "STOPPED") "STOPPED" else "COMPLETED"

            task.finalBalance = currentBalance
            task.profitAmount = profitAmount
            task.profitRate = profitRate
            task.endTime = endTime
            task.status = finalStatus
            task.progress = 100
            task.totalTrades = trades.size
            task.buyTrades = trades.count { it.side == "BUY" }
            task.sellTrades = trades.count { it.side == "SELL" }
            task.winTrades = statistics.winTrades
            task.lossTrades = statistics.lossTrades
            task.winRate = statistics.winRate.toSafeBigDecimal()
            task.maxProfit = statistics.maxProfit.toSafeBigDecimal()
            task.maxLoss = statistics.maxLoss.toSafeBigDecimal()
            task.maxDrawdown = statistics.maxDrawdown.toSafeBigDecimal()
            task.avgHoldingTime = statistics.avgHoldingTime
            task.executionFinishedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()

            backtestTaskRepository.save(task)

            logger.info("回测任务执行完成: taskId=${task.id}, " +
                "最终余额=${currentBalance.toPlainString()}, " +
                "收益额=${task.profitAmount?.toPlainString()}, " +
                "收益率=${task.profitRate?.toPlainString()}%, " +
                "总交易数=${trades.size}, " +
                "盈利率=${task.winRate?.toPlainString()}%")

        } catch (e: Exception) {
            logger.error("回测任务执行失败: taskId=${task.id}", e)
            task.status = "FAILED"
            task.errorMessage = e.message
            task.executionFinishedAt = System.currentTimeMillis()
            task.updatedAt = System.currentTimeMillis()
            backtestTaskRepository.save(task)
            throw e
        }
    }

    /**
     * 按时间点结算：当 resolved_at <= upToTime 时，按链上 0/1 结算。
     */
    private suspend fun settleResolvedPositionsUpTo(
        task: BacktestTask,
        positions: MutableMap<String, Position>,
        currentBalance: BigDecimal,
        settlementTrades: MutableList<BacktestTrade>,
        upToTime: Long
    ): BigDecimal {
        var balance = currentBalance

        for ((positionKey, position) in positions.toList()) {
            val resolvedAt = getMarketResolvedAt(position.marketId) ?: continue
            if (resolvedAt > upToTime) {
                continue
            }
            val idx = position.outcomeIndex ?: resolveOutcomeIndex(position.marketId, position.outcome, null)
            if (idx == null) {
                continue
            }
            position.outcomeIndex = idx
            val resolvedPrice = getResolvedSettlementPrice(position.marketId, idx) ?: continue

            val quantity = position.quantity
            val avgPrice = position.avgPrice
            val settlementValue = quantity.multiply(resolvedPrice)
            val profitLoss = settlementValue.subtract(quantity.multiply(avgPrice))
            val settlementOutcome = when {
                resolvedPrice.compareTo(BigDecimal.ONE) == 0 -> "WIN"
                resolvedPrice.compareTo(BigDecimal.ZERO) == 0 -> "LOSE"
                else -> "UNKNOWN"
            }

            balance += settlementValue

            settlementTrades.add(BacktestTrade(
                backtestTaskId = task.id!!,
                tradeTime = resolvedAt,
                marketId = position.marketId,
                marketTitle = "",
                side = "SETTLEMENT",
                outcome = settlementOutcome,
                outcomeIndex = position.outcomeIndex,
                quantity = quantity,
                price = resolvedPrice,
                amount = settlementValue,
                fee = BigDecimal.ZERO,
                profitLoss = profitLoss,
                balanceAfter = balance,
                leaderTradeId = null
            ))

            positions.remove(positionKey)
        }

        return balance
    }

    /**
     * 回测结束时对剩余持仓做估值（mark-to-market），用于对齐 Polymarket 页面包含未实现 PnL 的口径。
     * 注意：这里等价于“以 mark price 全部卖出”来计算账户总价值。
     */
    private suspend fun markToMarketRemainingPositionsAtBacktestEnd(
        task: BacktestTask,
        positions: MutableMap<String, Position>,
        currentBalance: BigDecimal,
        settlementTrades: MutableList<BacktestTrade>,
        currentTime: Long
    ): BigDecimal {
        var balance = currentBalance

        for ((_, position) in positions.toList()) {
            val quantity = position.quantity
            val avgPrice = position.avgPrice
            val idx = position.outcomeIndex ?: resolveOutcomeIndex(position.marketId, position.outcome, null)
            if (idx == null) {
                continue
            }
            position.outcomeIndex = idx

            val markPrice = try {
                marketPriceService.getMarkPrice(position.marketId, idx)
            } catch (e: Exception) {
                logger.debug("获取 mark price 失败，降级为成本价: marketId=${position.marketId}, outcomeIndex=$idx, error=${e.message}")
                avgPrice
            }

            val settlementValue = quantity.multiply(markPrice)

            balance += settlementValue

            settlementTrades.add(BacktestTrade(
                backtestTaskId = task.id!!,
                tradeTime = currentTime,
                marketId = position.marketId,
                marketTitle = "",
                side = "SETTLEMENT",
                outcome = "MARK",
                outcomeIndex = idx,
                quantity = quantity,
                price = markPrice,
                amount = settlementValue,
                fee = BigDecimal.ZERO,
                profitLoss = settlementValue.subtract(quantity.multiply(avgPrice)),
                balanceAfter = balance,
                leaderTradeId = null
            ))
        }

        positions.clear()
        return balance
    }

    /**
     * 获取市场 resolved_at（毫秒）。
     */
    private fun getMarketResolvedAt(marketId: String): Long? {
        return try {
            marketService.getMarket(marketId)?.resolvedAt
        } catch (e: Exception) {
            logger.debug("获取市场 resolved_at 失败: marketId=$marketId, error=${e.message}")
            null
        }
    }

    /**
     * 获取已 resolved 的结算价格（0/1）。
     * 未 resolved 或查询失败返回 null。
     */
    private suspend fun getResolvedSettlementPrice(marketId: String, outcomeIndex: Int): BigDecimal? {
        return try {
            marketPriceService.getResolvedMarketPrice(marketId, outcomeIndex)
        } catch (e: Exception) {
            logger.debug("获取 resolved 结算价格失败: marketId=$marketId, outcomeIndex=$outcomeIndex, error=${e.message}")
            null
        }
    }

    /**
     * 计算统计数据
     */
    private fun calculateStatistics(trades: List<BacktestTrade>): BacktestStatisticsDto {
        val buyTrades = trades.count { it.side == "BUY" }
        val sellTrades = trades.count { it.side == "SELL" }
        val winTrades = trades.count { it.profitLoss != null && it.profitLoss > BigDecimal.ZERO }
        val lossTrades = trades.count { it.profitLoss != null && it.profitLoss < BigDecimal.ZERO }

        var totalProfit = BigDecimal.ZERO
        var totalLoss = BigDecimal.ZERO
        var maxProfit = BigDecimal.ZERO
        var maxLoss = BigDecimal.ZERO

        // 计算最大回撤
        var runningBalance = if (trades.isNotEmpty()) {
            trades[0].balanceAfter?.toSafeBigDecimal() ?: BigDecimal.ZERO
        } else {
            BigDecimal.ZERO
        }
        var peakBalance = runningBalance
        var maxDrawdown = BigDecimal.ZERO

        for (i in trades.indices) {
            val trade = trades[i]
            val balance = trade.balanceAfter?.toSafeBigDecimal() ?: continue

            if (trade.profitLoss != null) {
                val pnl = trade.profitLoss.toSafeBigDecimal()
                if (pnl > BigDecimal.ZERO) {
                    totalProfit += pnl
                    if (pnl > maxProfit) maxProfit = pnl
                } else {
                    totalLoss += pnl
                    if (pnl < maxLoss) maxLoss = pnl
                }
            }

            if (balance > peakBalance) {
                peakBalance = balance
            }
            val drawdown = peakBalance - runningBalance
            if (drawdown > maxDrawdown) {
                maxDrawdown = drawdown
            }

            runningBalance = balance
        }

        // 计算平均持仓时间
        var avgHoldingTime: Long? = null
        if (trades.size > 1) {
            var totalHoldingTime = 0L
            var count = 0
            for (i in 0 until trades.size - 1) {
                val currentTrade = trades[i]
                val nextTrade = trades[i + 1]

                if (currentTrade.side == "BUY" && nextTrade.side == "SELL") {
                    val holdingTime = nextTrade.tradeTime - currentTrade.tradeTime
                    totalHoldingTime += holdingTime
                    count++
                }
            }

            if (count > 0) {
                avgHoldingTime = totalHoldingTime / count
            }
        }

        return BacktestStatisticsDto(
            totalTrades = trades.size,
            buyTrades = buyTrades,
            sellTrades = sellTrades,
            winTrades = winTrades,
            lossTrades = lossTrades,
            winRate = if (buyTrades + sellTrades > 0) {
                (winTrades.toBigDecimal().divide((buyTrades + sellTrades).toBigDecimal(), 4, java.math.RoundingMode.HALF_UP))
                    .multiply(BigDecimal("100"))
                    .toPlainString()
            } else {
                BigDecimal.ZERO.toPlainString()
            },
            maxProfit = maxProfit.toPlainString(),
            maxLoss = maxLoss.toPlainString(),
            maxDrawdown = maxDrawdown.toPlainString(),
            avgHoldingTime = avgHoldingTime
        )
    }

    /**
     * 计算跟单金额
     */
    private fun calculateFollowAmount(task: BacktestTask, leaderTrade: TradeData): BigDecimal {
        return if (task.copyMode == "RATIO") {
            // 比例模式：Leader 成交金额 × 跟单比例
            leaderTrade.amount.toSafeBigDecimal().multiply(task.copyRatio)
        } else {
            // 固定金额模式：使用配置的固定金额
            task.fixedAmount ?: leaderTrade.amount.toSafeBigDecimal()
        }
    }

    /**
     * BUY 成交价滑点：价格上浮（更差成交）
     */
    private fun applyBuySlippage(price: BigDecimal, slippagePercent: BigDecimal): BigDecimal {
        if (slippagePercent <= BigDecimal.ZERO) {
            return price
        }
        val factor = BigDecimal.ONE.add(slippagePercent.divide(hundred, 8, java.math.RoundingMode.HALF_UP))
        return price.multiply(factor).coerceAtLeast(minBacktestPrice)
    }

    /**
     * SELL 成交价滑点：价格下浮（更差成交）
     */
    private fun applySellSlippage(price: BigDecimal, slippagePercent: BigDecimal): BigDecimal {
        if (slippagePercent <= BigDecimal.ZERO) {
            return price
        }
        val factor = BigDecimal.ONE.subtract(slippagePercent.divide(hundred, 8, java.math.RoundingMode.HALF_UP))
        return price.multiply(factor).coerceAtLeast(minBacktestPrice)
    }

    /**
     * 判断是否同一天
     */
    private fun isSameDay(timestamp1: Long, timestamp2: Long): Boolean {
        val cal1 = Calendar.getInstance().apply { timeInMillis = timestamp1 }
        val cal2 = Calendar.getInstance().apply { timeInMillis = timestamp2 }
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
               cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
        return sdf.format(Date(timestamp))
    }

    /**
     * 格式化日期（用于缓存key）
     */
    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd")
        return sdf.format(Date(timestamp))
    }
}
