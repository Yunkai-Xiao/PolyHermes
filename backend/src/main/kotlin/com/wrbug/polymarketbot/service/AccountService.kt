package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.Account
import com.wrbug.polymarketbot.repository.AccountRepository
import com.wrbug.polymarketbot.util.CryptoUtils
import com.wrbug.polymarketbot.util.RetrofitFactory
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 账户管理服务
 */
@Service
class AccountService(
    private val accountRepository: AccountRepository,
    private val cryptoUtils: CryptoUtils,
    private val clobService: PolymarketClobService,
    private val retrofitFactory: RetrofitFactory,
    private val blockchainService: BlockchainService
) {
    
    private val logger = LoggerFactory.getLogger(AccountService::class.java)
    
    /**
     * 通过私钥导入账户
     */
    @Transactional
    fun importAccount(request: AccountImportRequest): Result<AccountDto> {
        return try {
            // 1. 验证钱包地址格式
            if (!isValidWalletAddress(request.walletAddress)) {
                return Result.failure(IllegalArgumentException("无效的钱包地址格式"))
            }
            
            // 2. 检查地址是否已存在
            if (accountRepository.existsByWalletAddress(request.walletAddress)) {
                return Result.failure(IllegalArgumentException("该钱包地址已存在"))
            }
            
            // 3. 验证私钥和地址的对应关系
            // 注意：前端已经验证了私钥和地址的对应关系，这里只做格式验证
            // 如果需要更严格的验证，可以使用以太坊库（如 web3j）进行验证
            if (!isValidPrivateKey(request.privateKey)) {
                return Result.failure(IllegalArgumentException("无效的私钥格式"))
            }
            
            // 4. 加密私钥和 API 凭证
            val encryptedPrivateKey = cryptoUtils.encrypt(request.privateKey)
            val encryptedApiKey = request.apiKey?.let { cryptoUtils.encrypt(it) }
            val encryptedApiSecret = request.apiSecret?.let { cryptoUtils.encrypt(it) }
            val encryptedApiPassphrase = request.apiPassphrase?.let { cryptoUtils.encrypt(it) }
            
            // 5. 如果设置为默认账户，取消其他账户的默认状态
            if (request.isDefault) {
                accountRepository.findByIsDefaultTrue()?.let { defaultAccount ->
                    val updated = defaultAccount.copy(isDefault = false, updatedAt = System.currentTimeMillis())
                    accountRepository.save(updated)
                }
            }
            
            // 6. 获取代理地址（必须成功，否则导入失败）
            val proxyAddress = runBlocking {
                val proxyResult = blockchainService.getProxyAddress(request.walletAddress)
                if (proxyResult.isSuccess) {
                    val address = proxyResult.getOrNull()
                    if (address != null) {
                        logger.info("成功获取代理地址: ${request.walletAddress} -> $address")
                        address
                    } else {
                        logger.error("获取代理地址返回空值")
                        throw IllegalStateException("获取代理地址失败：返回值为空")
                    }
                } else {
                    val error = proxyResult.exceptionOrNull()
                    logger.error("获取代理地址失败: ${error?.message}")
                    throw IllegalStateException("获取代理地址失败: ${error?.message}。请确保已配置 Ethereum RPC URL 且 RPC 节点可用")
                }
            }
            
            // 7. 创建账户
            val account = Account(
                privateKey = encryptedPrivateKey,
                walletAddress = request.walletAddress,
                proxyAddress = proxyAddress,
                apiKey = encryptedApiKey,
                apiSecret = encryptedApiSecret,
                apiPassphrase = encryptedApiPassphrase,
                accountName = request.accountName,
                isDefault = request.isDefault,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = accountRepository.save(account)
            logger.info("成功导入账户: ${saved.id}, ${saved.walletAddress}, 代理地址: ${saved.proxyAddress}")
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("导入账户失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新账户信息
     */
    @Transactional
    fun updateAccount(request: AccountUpdateRequest): Result<AccountDto> {
        return try {
            val account = accountRepository.findById(request.accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))
            
            // 更新账户名称
            val updatedAccountName = request.accountName ?: account.accountName
            
            // 更新 API 凭证
            val updatedApiKey = if (request.apiKey != null) {
                cryptoUtils.encrypt(request.apiKey)
            } else {
                account.apiKey
            }
            val updatedApiSecret = if (request.apiSecret != null) {
                cryptoUtils.encrypt(request.apiSecret)
            } else {
                account.apiSecret
            }
            val updatedApiPassphrase = if (request.apiPassphrase != null) {
                cryptoUtils.encrypt(request.apiPassphrase)
            } else {
                account.apiPassphrase
            }
            
            // 如果设置为默认账户，取消其他账户的默认状态
            val updatedIsDefault = request.isDefault ?: account.isDefault
            if (updatedIsDefault && !account.isDefault) {
                accountRepository.findByIsDefaultTrue()?.let { defaultAccount ->
                    val updated = defaultAccount.copy(isDefault = false, updatedAt = System.currentTimeMillis())
                    accountRepository.save(updated)
                }
            }
            
            val updated = account.copy(
                accountName = updatedAccountName,
                apiKey = updatedApiKey,
                apiSecret = updatedApiSecret,
                apiPassphrase = updatedApiPassphrase,
                isDefault = updatedIsDefault,
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = accountRepository.save(updated)
            logger.info("成功更新账户: ${saved.id}")
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新账户失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除账户
     */
    @Transactional
    fun deleteAccount(accountId: Long): Result<Unit> {
        return try {
            val account = accountRepository.findById(accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))
            
            // 注意：不再检查活跃订单，允许用户删除有活跃订单的账户
            // 前端会显示确认提示框，由用户决定是否删除
            
            // 如果删除的是默认账户，需要先设置其他账户为默认
            if (account.isDefault) {
                val otherAccounts = accountRepository.findAllByOrderByCreatedAtAsc()
                    .filter { it.id != accountId }
                
                if (otherAccounts.isNotEmpty()) {
                    val newDefault = otherAccounts.first().copy(
                        isDefault = true,
                        updatedAt = System.currentTimeMillis()
                    )
                    accountRepository.save(newDefault)
                } else {
                    return Result.failure(IllegalStateException("不能删除最后一个账户"))
                }
            }
            
            accountRepository.delete(account)
            logger.info("成功删除账户: $accountId")
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除账户失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询账户列表
     */
    fun getAccountList(): Result<AccountListResponse> {
        return try {
            val accounts = accountRepository.findAllByOrderByCreatedAtAsc()
            val accountDtos = accounts.map { toDto(it) }
            
            Result.success(AccountListResponse(
                list = accountDtos,
                total = accountDtos.size.toLong()
            ))
        } catch (e: Exception) {
            logger.error("查询账户列表失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询账户详情
     */
    fun getAccountDetail(accountId: Long?): Result<AccountDto> {
        return try {
            val account = if (accountId != null) {
                accountRepository.findById(accountId).orElse(null)
            } else {
                accountRepository.findByIsDefaultTrue()
            }
            
            account ?: return Result.failure(IllegalArgumentException("账户不存在"))
            
            Result.success(toDto(account))
        } catch (e: Exception) {
            logger.error("查询账户详情失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询账户余额
     * 通过链上 RPC 查询 USDC 余额，并通过 Subgraph API 查询持仓信息
     */
    fun getAccountBalance(accountId: Long?): Result<AccountBalanceResponse> {
        return try {
            val account = if (accountId != null) {
                accountRepository.findById(accountId).orElse(null)
            } else {
                accountRepository.findByIsDefaultTrue()
            }
            
            account ?: return Result.failure(IllegalArgumentException("账户不存在"))
            
            // 检查代理地址是否存在
            if (account.proxyAddress.isBlank()) {
                logger.error("账户 ${account.id} 的代理地址为空，无法查询余额")
                return Result.failure(IllegalStateException("账户代理地址不存在，无法查询余额。请重新导入账户以获取代理地址"))
            }
            
            // 查询 USDC 余额和持仓信息
            val balanceResult = runBlocking {
                try {
                    // 先查询持仓信息（用于计算仓位余额和返回持仓列表）
                    // 使用代理地址查询持仓（Polymarket 使用代理地址存储持仓）
                    val positionsResult = blockchainService.getPositions(account.proxyAddress)
                    val positions = if (positionsResult.isSuccess) {
                        positionsResult.getOrNull()?.map { pos ->
                            PositionDto(
                                marketId = pos.conditionId ?: "",
                                side = pos.outcome ?: "",
                                quantity = pos.size?.toString() ?: "0",
                                avgPrice = pos.avgPrice?.toString() ?: "0",
                                currentValue = pos.currentValue?.toString() ?: "0",
                                pnl = pos.cashPnl?.toString()
                            )
                        } ?: emptyList()
                    } else {
                        logger.warn("持仓信息查询失败: ${positionsResult.exceptionOrNull()?.message}")
                        emptyList()
                    }
                    
                    // 计算仓位余额（持仓总价值）
                    val positionBalance = positions.sumOf {
                        it.currentValue.toSafeBigDecimal()
                    }
                    
                    // 查询可用余额（通过 RPC 查询 USDC 余额）
                    // 必须使用代理地址查询
                    val availableBalanceResult = blockchainService.getUsdcBalance(
                        walletAddress = account.walletAddress,
                        proxyAddress = account.proxyAddress
                    )
                    val availableBalance = if (availableBalanceResult.isSuccess) {
                        availableBalanceResult.getOrNull() ?: throw Exception("USDC 余额查询返回空值")
                    } else {
                        // 如果 RPC 查询失败，返回错误（不返回 mock 数据）
                        val error = availableBalanceResult.exceptionOrNull()
                        logger.error("USDC 可用余额 RPC 查询失败: ${error?.message}")
                        throw Exception("USDC 可用余额查询失败: ${error?.message}。请确保已配置 Ethereum RPC URL")
                    }
                    
                    // 计算总余额 = 可用余额 + 仓位余额
                    val totalBalance = availableBalance.toSafeBigDecimal().add(positionBalance)
                    
                    AccountBalanceResponse(
                        availableBalance = availableBalance,
                        positionBalance = positionBalance.toPlainString(),
                        totalBalance = totalBalance.toPlainString(),
                        positions = positions
                    )
                } catch (e: Exception) {
                    logger.error("查询余额失败: ${e.message}", e)
                    throw e
                }
            }
            
            Result.success(balanceResult)
        } catch (e: Exception) {
            logger.error("查询账户余额失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 设置默认账户
     */
    @Transactional
    fun setDefaultAccount(accountId: Long): Result<Unit> {
        return try {
            val account = accountRepository.findById(accountId)
                .orElse(null) ?: return Result.failure(IllegalArgumentException("账户不存在"))
            
            // 取消其他账户的默认状态
            accountRepository.findByIsDefaultTrue()?.let { defaultAccount ->
                if (defaultAccount.id != account.id) {
                    val updated = defaultAccount.copy(isDefault = false, updatedAt = System.currentTimeMillis())
                    accountRepository.save(updated)
                }
            }
            
            // 设置当前账户为默认
            val updated = account.copy(isDefault = true, updatedAt = System.currentTimeMillis())
            accountRepository.save(updated)
            
            logger.info("成功设置默认账户: $accountId")
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("设置默认账户失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 转换为 DTO
     * 包含交易统计数据（总订单数和总盈亏）
     */
    private fun toDto(account: Account): AccountDto {
        return runBlocking {
            val statistics = getAccountStatistics(account)
            AccountDto(
                id = account.id!!,
                walletAddress = account.walletAddress,
                accountName = account.accountName,
                isDefault = account.isDefault,
                apiKeyConfigured = account.apiKey != null,
                apiSecretConfigured = account.apiSecret != null,
                apiPassphraseConfigured = account.apiPassphrase != null,
                totalOrders = statistics.totalOrders,
                totalPnl = statistics.totalPnl
            )
        }
    }
    
    /**
     * 获取账户交易统计数据
     */
    private suspend fun getAccountStatistics(account: Account): AccountStatistics {
        return try {
            // 如果账户没有配置 API 凭证，无法查询统计数据
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                return AccountStatistics(totalOrders = null, totalPnl = null)
            }
            
            // 解密 API 凭证
            val apiKey = cryptoUtils.decrypt(account.apiKey)
            val apiSecret = cryptoUtils.decrypt(account.apiSecret)
            val apiPassphrase = cryptoUtils.decrypt(account.apiPassphrase)
            
            // 创建带认证的 API 客户端
            val clobApi = retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase)
            
            // 1. 查询交易记录数量（总订单数）
            val tradesResult = runBlocking {
                try {
                    // 使用代理地址查询交易记录
                    val response = clobApi.getTrades(
                        maker_address = account.proxyAddress,
                        next_cursor = null
                    )
                    if (response.isSuccessful && response.body() != null) {
                        val tradesResponse = response.body()!!
                        // 统计所有交易（需要分页查询所有）
                        var totalTrades = tradesResponse.data.size
                        var nextCursor = tradesResponse.next_cursor
                        
                        // 分页查询所有交易
                        while (nextCursor != null && nextCursor.isNotEmpty()) {
                            val nextResponse = clobApi.getTrades(
                                maker_address = account.proxyAddress,
                                next_cursor = nextCursor
                            )
                            if (nextResponse.isSuccessful && nextResponse.body() != null) {
                                val nextTradesResponse = nextResponse.body()!!
                                totalTrades += nextTradesResponse.data.size
                                nextCursor = nextTradesResponse.next_cursor
                            } else {
                                break
                            }
                        }
                        Result.success(totalTrades.toLong())
                    } else {
                        Result.failure(Exception("查询交易记录失败: ${response.code()} ${response.message()}"))
                    }
                } catch (e: Exception) {
                    logger.warn("查询交易记录失败: ${e.message}", e)
                    Result.failure(e)
                }
            }
            
            // 2. 查询仓位信息计算总盈亏（已实现盈亏）
            val totalPnlResult = runBlocking {
                try {
                    val positionsResult = blockchainService.getPositions(account.proxyAddress)
                    if (positionsResult.isSuccess) {
                        val positions = positionsResult.getOrNull() ?: emptyList()
                        // 汇总所有仓位的已实现盈亏
                        val totalRealizedPnl = positions.sumOf { pos ->
                            pos.realizedPnl?.toSafeBigDecimal() ?: BigDecimal.ZERO
                        }
                        Result.success(totalRealizedPnl.toPlainString())
                    } else {
                        Result.failure(Exception("查询仓位信息失败"))
                    }
                } catch (e: Exception) {
                    logger.warn("查询仓位盈亏失败: ${e.message}", e)
                    Result.failure(e)
                }
            }
            
            AccountStatistics(
                totalOrders = tradesResult.getOrNull(),
                totalPnl = totalPnlResult.getOrNull()
            )
        } catch (e: Exception) {
            logger.warn("获取账户统计数据失败: ${e.message}", e)
            AccountStatistics(totalOrders = null, totalPnl = null)
        }
    }
    
    /**
     * 账户统计数据
     */
    private data class AccountStatistics(
        val totalOrders: Long?,
        val totalPnl: String?
    )
    
    /**
     * 验证钱包地址格式
     */
    private fun isValidWalletAddress(address: String): Boolean {
        // 以太坊地址格式：0x 开头，42 位字符
        return address.startsWith("0x") && address.length == 42 && address.matches(Regex("^0x[0-9a-fA-F]{40}$"))
    }
    
    /**
     * 验证私钥格式
     */
    private fun isValidPrivateKey(privateKey: String): Boolean {
        // 私钥格式：64 位十六进制字符（可选 0x 前缀）
        val cleanKey = if (privateKey.startsWith("0x")) privateKey.substring(2) else privateKey
        return cleanKey.length == 64 && cleanKey.matches(Regex("^[0-9a-fA-F]{64}$"))
    }
    
    /**
     * 检查账户是否有活跃订单
     * 使用账户的 API Key 查询该账户的活跃订单
     */
    private suspend fun hasActiveOrders(account: Account): Boolean {
        return try {
            // 如果账户没有配置 API 凭证，无法查询活跃订单，允许删除
            if (account.apiKey == null || account.apiSecret == null || account.apiPassphrase == null) {
                logger.debug("账户 ${account.id} 未配置 API 凭证，无法查询活跃订单，允许删除")
                return false
            }
            
            // 解密 API 凭证（前面已检查不为 null）
            val apiKey = cryptoUtils.decrypt(account.apiKey)
            val apiSecret = cryptoUtils.decrypt(account.apiSecret)
            val apiPassphrase = cryptoUtils.decrypt(account.apiPassphrase)
            
            // 创建带认证的 API 客户端
            val clobApi = retrofitFactory.createClobApi(apiKey, apiSecret, apiPassphrase)
            
            // 查询活跃订单（只查询第一条，用于判断是否有订单）
            // 使用 next_cursor 参数进行分页，这里只查询第一页
            val response = clobApi.getActiveOrders(
                id = null,
                market = null,
                asset_id = null,
                next_cursor = null  // null 表示从第一页开始
            )
            
            if (response.isSuccessful && response.body() != null) {
                val ordersResponse = response.body()!!
                val hasOrders = ordersResponse.data.isNotEmpty()
                logger.debug("账户 ${account.id} 活跃订单检查结果: $hasOrders (订单数: ${ordersResponse.data.size})")
                hasOrders
            } else {
                // 如果查询失败（可能是认证失败或网络问题），记录警告但允许删除
                // 因为无法确定是否有活跃订单，不应该阻止删除操作
                logger.warn("查询活跃订单失败: ${response.code()} ${response.message()}，允许删除账户")
                false
            }
        } catch (e: Exception) {
            // 如果查询异常（网络问题、API 错误等），记录警告但允许删除
            // 因为无法确定是否有活跃订单，不应该阻止删除操作
            logger.warn("检查活跃订单异常: ${e.message}，允许删除账户", e)
            false
        }
    }
}


