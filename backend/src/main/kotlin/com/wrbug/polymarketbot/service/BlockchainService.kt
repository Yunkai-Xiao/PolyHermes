package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.api.EthereumRpcApi
import com.wrbug.polymarketbot.api.JsonRpcRequest
import com.wrbug.polymarketbot.api.JsonRpcResponse
import com.wrbug.polymarketbot.api.PolymarketDataApi
import com.wrbug.polymarketbot.api.PositionResponse
import com.wrbug.polymarketbot.api.ValueResponse
import com.wrbug.polymarketbot.util.EthereumUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.createClient
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.math.BigDecimal
import java.math.BigInteger

/**
 * 区块链查询服务
 * 用于查询链上余额和持仓信息
 */
@Service
class BlockchainService(
    @Value("\${polymarket.data-api.base-url:https://data-api.polymarket.com}")
    private val dataApiBaseUrl: String,
    @Value("\${ethereum.rpc.url:}")
    private val ethereumRpcUrl: String,
    private val retrofitFactory: RetrofitFactory
) {
    
    private val logger = LoggerFactory.getLogger(BlockchainService::class.java)
    
    // USDC 合约地址（Polygon 主网，Polymarket 使用 Polygon）
    private val usdcContractAddress = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174"
    
    // Polymarket 代理工厂合约地址（Polygon 主网）
    // 合约地址: 0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b
    private val proxyFactoryContractAddress = "0xaacFeEa03eb1561C4e67d661e40682Bd20E3541b"
    
    // ConditionalTokens 合约地址（Polygon 主网）
    private val conditionalTokensAddress = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045"
    
    // 空集合ID（用于计算collectionId）
    private val EMPTY_SET = "0x0000000000000000000000000000000000000000000000000000000000000000"
    
    // 获取代理地址的函数签名
    // 根据 Polygonscan 的 F4 方法，函数签名为: computeProxyAddress(address)
    private val computeProxyAddressFunctionSignature = "computeProxyAddress(address)"
    
    private val dataApi: PolymarketDataApi by lazy {
        val baseUrl = if (dataApiBaseUrl.endsWith("/")) {
            dataApiBaseUrl.dropLast(1)
        } else {
            dataApiBaseUrl
        }
        val okHttpClient = createClient()
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        Retrofit.Builder()
            .baseUrl("$baseUrl/")
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(PolymarketDataApi::class.java)
    }
    
    private val ethereumRpcApi: EthereumRpcApi? by lazy {
        if (ethereumRpcUrl.isBlank()) {
            null
        } else {
            retrofitFactory.createEthereumRpcApi(ethereumRpcUrl)
        }
    }
    
    /**
     * 获取 Polymarket 代理钱包地址
     * 通过 RPC 调用代理工厂合约获取用户的代理钱包地址
     * @param walletAddress 用户的钱包地址
     * @return 代理钱包地址
     */
    suspend fun getProxyAddress(walletAddress: String): Result<String> {
        return try {
            // 如果未配置 RPC URL，返回错误
            if (ethereumRpcUrl.isBlank()) {
                logger.warn("未配置 Ethereum RPC URL，无法获取代理地址")
                return Result.failure(IllegalStateException("未配置 Ethereum RPC URL，无法获取代理地址。请在配置文件中设置 ethereum.rpc.url 环境变量"))
            }
            
            val rpcApi = ethereumRpcApi ?: throw IllegalStateException("Ethereum RPC URL 未配置")
            
            // 计算函数选择器
            val functionSelector = EthereumUtils.getFunctionSelector(computeProxyAddressFunctionSignature)
            // 编码地址参数
            val encodedAddress = EthereumUtils.encodeAddress(walletAddress)
            // 构建调用数据
            val data = functionSelector + encodedAddress
            
            // 构建 JSON-RPC 请求
            val rpcRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to proxyFactoryContractAddress,
                        "data" to data
                    ),
                    "latest"
                )
            )
            
            // 发送 RPC 请求
            val response = rpcApi.call(rpcRequest)
            
            if (!response.isSuccessful || response.body() == null) {
                throw Exception("RPC 请求失败: ${response.code()} ${response.message()}")
            }
            
            val rpcResponse = response.body()!!
            
            // 检查错误
            if (rpcResponse.error != null) {
                throw Exception("RPC 错误: ${rpcResponse.error.message}")
            }
            
            val hexResult = rpcResponse.result ?: throw Exception("RPC 响应格式错误: result 为空")
            
            // 解析代理地址
            val proxyAddress = EthereumUtils.decodeAddress(hexResult)
            
            logger.debug("获取代理地址成功: 原始地址=$walletAddress, 代理地址=$proxyAddress")
            Result.success(proxyAddress)
        } catch (e: Exception) {
            logger.error("获取代理地址失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询账户 USDC 余额
     * 通过 Ethereum RPC 查询 ERC-20 代币余额
     * @param walletAddress 钱包地址（用于日志记录）
     * @param proxyAddress 代理地址（必须提供）
     * 如果 RPC 未配置或代理地址为空，返回失败（不返回 mock 数据）
     */
    suspend fun getUsdcBalance(walletAddress: String, proxyAddress: String): Result<String> {
        return try {
            // 如果未配置 RPC URL，返回错误
            if (ethereumRpcUrl.isBlank()) {
                logger.warn("未配置 Ethereum RPC URL，无法查询 USDC 余额")
                return Result.failure(IllegalStateException("未配置 Ethereum RPC URL，无法查询 USDC 余额。请在配置文件中设置 ethereum.rpc.url 环境变量"))
            }
            
            // 检查代理地址是否为空
            if (proxyAddress.isBlank()) {
                logger.error("代理地址为空，无法查询余额")
                return Result.failure(IllegalArgumentException("代理地址不能为空"))
            }
            
            logger.debug("使用代理地址查询余额: $proxyAddress (原始地址: $walletAddress)")
            
            // 使用 RPC 查询 USDC 余额（使用代理地址）
            val balance = queryUsdcBalanceViaRpc(proxyAddress)
            Result.success(balance)
        } catch (e: Exception) {
            logger.error("查询 USDC 余额失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 通过 RPC 查询 USDC 余额
     */
    private suspend fun queryUsdcBalanceViaRpc(walletAddress: String): String {
        val rpcApi = ethereumRpcApi ?: throw IllegalStateException("Ethereum RPC URL 未配置")
        
        // 构建 ERC-20 balanceOf 函数调用
        // function signature: balanceOf(address) -> bytes4(0x70a08231)
        // 参数编码: address (32 bytes, padded)
        val functionSelector = "0x70a08231" // balanceOf(address)
        val paddedAddress = walletAddress.removePrefix("0x").lowercase().padStart(64, '0')
        val data = functionSelector + paddedAddress
        
        // 构建 JSON-RPC 请求
        val rpcRequest = JsonRpcRequest(
            method = "eth_call",
            params = listOf(
                mapOf(
                    "to" to usdcContractAddress,
                    "data" to data
                ),
                "latest"
            )
        )
        
        // 发送 RPC 请求（使用 Retrofit）
        val response = rpcApi.call(rpcRequest)
        
        if (!response.isSuccessful || response.body() == null) {
            throw Exception("RPC 请求失败: ${response.code()} ${response.message()}")
        }
        
        val rpcResponse = response.body()!!
        
        // 检查错误
        if (rpcResponse.error != null) {
            throw Exception("RPC 错误: ${rpcResponse.error.message}")
        }
        
        val hexBalance = rpcResponse.result ?: throw Exception("RPC 响应格式错误: result 为空")
        
        // 将十六进制转换为 BigDecimal（USDC 有 6 位小数）
        val balanceWei = BigInteger(hexBalance.removePrefix("0x"), 16)
        val balance = BigDecimal(balanceWei).divide(BigDecimal("1000000")) // USDC 有 6 位小数
        
        return balance.toPlainString()
    }
    
    /**
     * 查询账户持仓信息
     * 通过 Polymarket Data API 查询
     * 文档: https://docs.polymarket.com/api-reference/core/get-current-positions-for-a-user
     */
    suspend fun getPositions(proxyWalletAddress: String, sortBy: String? = "CURRENT"): Result<List<PositionResponse>> {
        return try {
            // 使用代理钱包地址查询仓位
            // sortBy=CURRENT 表示只返回当前仓位
            val response = dataApi.getPositions(
                user = proxyWalletAddress,
                limit = 500,  // 最大限制
                offset = 0,
                sortBy = sortBy
            )
            
            if (response.isSuccessful && response.body() != null) {
                val positions = response.body()!!
                logger.debug("查询到 ${positions.size} 个仓位")
                Result.success(positions)
            } else {
                val errorMsg = "Data API 请求失败: ${response.code()} ${response.message()}"
                logger.error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            logger.error("查询持仓信息失败: ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 从 condition ID 和 outcomeIndex 计算 tokenId
     * 使用链上合约调用计算：
     * 1. getCollectionId(EMPTY_SET, conditionId, indexSet) -> collectionId
     * 2. getPositionId(collateralToken, collectionId) -> tokenId
     * 
     * indexSet 的计算：indexSet = 2^outcomeIndex
     * - outcomeIndex = 0 -> indexSet = 1 (2^0)
     * - outcomeIndex = 1 -> indexSet = 2 (2^1)
     * - outcomeIndex = 2 -> indexSet = 4 (2^2)
     * 
     * @param conditionId condition ID（16进制字符串，如 "0x..."）
     * @param outcomeIndex 结果索引（0, 1, 2...）
     * @return tokenId（BigInteger 的字符串表示）
     */
    suspend fun getTokenId(conditionId: String, outcomeIndex: Int): Result<String> {
        return try {
            // 如果未配置 RPC URL，返回错误
            if (ethereumRpcUrl.isBlank()) {
                logger.warn("未配置 Ethereum RPC URL，无法计算 tokenId")
                return Result.failure(IllegalStateException("未配置 Ethereum RPC URL，无法计算 tokenId"))
            }
            
            val rpcApi = ethereumRpcApi ?: throw IllegalStateException("Ethereum RPC URL 未配置")
            
            // 验证 outcomeIndex
            if (outcomeIndex < 0) {
                return Result.failure(IllegalArgumentException("outcomeIndex 必须 >= 0"))
            }
            
            // 计算 indexSet：indexSet = 2^outcomeIndex
            val indexSet = BigInteger.TWO.pow(outcomeIndex)
            
            // 1. 调用 getCollectionId(EMPTY_SET, conditionId, indexSet)
            val getCollectionIdSelector = EthereumUtils.getFunctionSelector("getCollectionId(bytes32,bytes32,uint256)")
            val encodedEmptySet = EthereumUtils.encodeBytes32(EMPTY_SET)
            val encodedConditionId = EthereumUtils.encodeBytes32(conditionId)
            val encodedIndexSet = EthereumUtils.encodeUint256(indexSet)
            // getFunctionSelector 已经返回带 0x 前缀的字符串，所以直接拼接即可
            val collectionIdData = getCollectionIdSelector + encodedEmptySet + encodedConditionId + encodedIndexSet
            
            val collectionIdRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to collectionIdData  // 移除多余的 0x 前缀
                    ),
                    "latest"
                )
            )
            
            val collectionIdResponse = rpcApi.call(collectionIdRequest)
            if (!collectionIdResponse.isSuccessful || collectionIdResponse.body() == null) {
                return Result.failure(Exception("调用 getCollectionId 失败: ${collectionIdResponse.code()} ${collectionIdResponse.message()}"))
            }
            
            val collectionIdResult = collectionIdResponse.body()!!
            if (collectionIdResult.error != null) {
                return Result.failure(Exception("调用 getCollectionId 失败: ${collectionIdResult.error}"))
            }
            
            val collectionId = collectionIdResult.result ?: return Result.failure(Exception("getCollectionId 返回结果为空"))
            
            // 2. 调用 getPositionId(collateralToken, collectionId)
            val getPositionIdSelector = EthereumUtils.getFunctionSelector("getPositionId(address,bytes32)")
            val encodedCollateral = EthereumUtils.encodeAddress(usdcContractAddress)
            val encodedCollectionId = EthereumUtils.encodeBytes32(collectionId)
            // getFunctionSelector 已经返回带 0x 前缀的字符串，所以直接拼接即可
            val positionIdData = getPositionIdSelector + encodedCollateral + encodedCollectionId
            
            val positionIdRequest = JsonRpcRequest(
                method = "eth_call",
                params = listOf(
                    mapOf(
                        "to" to conditionalTokensAddress,
                        "data" to positionIdData  // 移除多余的 0x 前缀
                    ),
                    "latest"
                )
            )
            
            val positionIdResponse = rpcApi.call(positionIdRequest)
            if (!positionIdResponse.isSuccessful || positionIdResponse.body() == null) {
                return Result.failure(Exception("调用 getPositionId 失败: ${positionIdResponse.code()} ${positionIdResponse.message()}"))
            }
            
            val positionIdResult = positionIdResponse.body()!!
            if (positionIdResult.error != null) {
                return Result.failure(Exception("调用 getPositionId 失败: ${positionIdResult.error}"))
            }
            
            val tokenId = positionIdResult.result ?: return Result.failure(Exception("getPositionId 返回结果为空"))
            val tokenIdBigInt = EthereumUtils.decodeUint256(tokenId)
            
            Result.success(tokenIdBigInt.toString())
        } catch (e: Exception) {
            logger.error("计算 tokenId 失败: conditionId=$conditionId, outcomeIndex=$outcomeIndex, ${e.message}", e)
            Result.failure(e)
        }
    }
    
    /**
     * 从 condition ID 和 side (YES/NO) 计算 tokenId（向后兼容方法）
     * 仅支持二元市场（YES/NO）
     * 
     * @param conditionId condition ID（16进制字符串，如 "0x..."）
     * @param side YES 或 NO
     * @return tokenId（BigInteger 的字符串表示）
     */
    suspend fun getTokenIdBySide(conditionId: String, side: String): Result<String> {
        val outcomeIndex = when (side.uppercase()) {
            "YES" -> 0
            "NO" -> 1
            else -> return Result.failure(IllegalArgumentException("side 必须是 YES 或 NO（仅支持二元市场）"))
        }
        return getTokenId(conditionId, outcomeIndex)
    }
    
    /**
     * 获取用户仓位总价值
     * 通过 Polymarket Data API 查询
     * 文档: https://docs.polymarket.com/api-reference/core/get-total-value-of-a-users-positions
     */
    suspend fun getTotalValue(proxyWalletAddress: String): Result<String> {
        return try {
            // 使用代理钱包地址查询仓位总价值
            val response = dataApi.getTotalValue(
                user = proxyWalletAddress,
                market = null
            )
            
            if (response.isSuccessful && response.body() != null) {
                val values = response.body()!!
                // 根据文档，返回的是数组，通常只有一个元素
                val totalValue = if (values.isNotEmpty()) {
                    values.first().value
                } else {
                    0.0
                }
                logger.debug("查询到仓位总价值: $totalValue")
                Result.success(totalValue.toString())
            } else {
                val errorMsg = "Data API 请求失败: ${response.code()} ${response.message()}"
                logger.error(errorMsg)
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            logger.error("查询仓位总价值失败: ${e.message}", e)
            Result.failure(e)
        }
    }
}

