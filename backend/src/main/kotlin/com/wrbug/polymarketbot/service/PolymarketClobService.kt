package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.api.*
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * Polymarket CLOB API 服务封装
 * 提供订单操作、市场数据、交易数据等功能
 */
@Service
class PolymarketClobService(
    private val clobApi: PolymarketClobApi
) {
    
    private val logger = LoggerFactory.getLogger(PolymarketClobService::class.java)
    
    /**
     * 获取订单簿
     */
    suspend fun getOrderbook(market: String): Result<OrderbookResponse> {
        return try {
            val response = clobApi.getOrderbook(market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("获取订单簿失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取订单簿异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取价格信息
     */
    suspend fun getPrice(market: String): Result<PriceResponse> {
        return try {
            val response = clobApi.getPrice(market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("获取价格失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取价格异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取中间价
     */
    suspend fun getMidpoint(market: String): Result<MidpointResponse> {
        return try {
            val response = clobApi.getMidpoint(market)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("获取中间价失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取中间价异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 创建订单
     */
    suspend fun createOrder(request: CreateOrderRequest): Result<OrderResponse> {
        return try {
            val response = clobApi.createOrder(request)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("创建订单失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("创建订单异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取活跃订单
     */
    suspend fun getActiveOrders(
        id: String? = null,
        market: String? = null,
        asset_id: String? = null,
        next_cursor: String? = null
    ): Result<List<OrderResponse>> {
        return try {
            val response = clobApi.getActiveOrders(
                id = id,
                market = market,
                asset_id = asset_id,
                next_cursor = next_cursor
            )
            if (response.isSuccessful && response.body() != null) {
                val ordersResponse = response.body()!!
                Result.success(ordersResponse.data)
            } else {
                Result.failure(Exception("获取活跃订单失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取活跃订单异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 取消订单
     */
    suspend fun cancelOrder(orderId: String): Result<CancelOrderResponse> {
        return try {
            val response = clobApi.cancelOrder(orderId)
            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("取消订单失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("取消订单异常: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 获取交易记录
     */
    suspend fun getTrades(
        id: String? = null,
        maker_address: String? = null,
        market: String? = null,
        asset_id: String? = null,
        before: String? = null,
        after: String? = null,
        next_cursor: String? = null
    ): Result<List<TradeResponse>> {
        return try {
            val response = clobApi.getTrades(
                id = id,
                maker_address = maker_address,
                market = market,
                asset_id = asset_id,
                before = before,
                after = after,
                next_cursor = next_cursor
            )
            if (response.isSuccessful && response.body() != null) {
                val tradesResponse = response.body()!!
                Result.success(tradesResponse.data)
            } else {
                Result.failure(Exception("获取交易记录失败: ${response.code()} ${response.message()}"))
            }
        } catch (e: Exception) {
            logger.error("获取交易记录异常: ${e.message}", e)
            Result.failure(e)
        }
    }
}

