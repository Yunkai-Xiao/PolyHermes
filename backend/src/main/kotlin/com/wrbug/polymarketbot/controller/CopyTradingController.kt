package com.wrbug.polymarketbot.controller

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.enums.ErrorCode
import com.wrbug.polymarketbot.service.CopyTradingService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 跟单配置管理控制器（钱包-模板关联）
 */
@RestController
@RequestMapping("/api/copy-trading")
class CopyTradingController(
    private val copyTradingService: CopyTradingService
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingController::class.java)
    
    /**
     * 创建跟单
     */
    @PostMapping("/create")
    fun createCopyTrading(@RequestBody request: CopyTradingCreateRequest): ResponseEntity<ApiResponse<CopyTradingDto>> {
        return try {
            if (request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID))
            }
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_TEMPLATE_ID_INVALID))
            }
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_LEADER_ID_INVALID))
            }
            
            val result = copyTradingService.createCopyTrading(request)
            result.fold(
                onSuccess = { copyTrading ->
                    ResponseEntity.ok(ApiResponse.success(copyTrading))
                },
                onFailure = { e ->
                    logger.error("创建跟单失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_CREATE_FAILED, e.message))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("创建跟单异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_CREATE_FAILED, e.message))
        }
    }
    
    /**
     * 查询跟单列表
     */
    @PostMapping("/list")
    fun getCopyTradingList(@RequestBody request: CopyTradingListRequest): ResponseEntity<ApiResponse<CopyTradingListResponse>> {
        return try {
            val result = copyTradingService.getCopyTradingList(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询跟单列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_LIST_FETCH_FAILED, e.message))
                }
            )
        } catch (e: Exception) {
            logger.error("查询跟单列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_LIST_FETCH_FAILED, e.message))
        }
    }
    
    /**
     * 更新跟单状态
     */
    @PostMapping("/update-status")
    fun updateCopyTradingStatus(@RequestBody request: CopyTradingUpdateStatusRequest): ResponseEntity<ApiResponse<CopyTradingDto>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID))
            }
            
            val result = copyTradingService.updateCopyTradingStatus(request)
            result.fold(
                onSuccess = { copyTrading ->
                    ResponseEntity.ok(ApiResponse.success(copyTrading))
                },
                onFailure = { e ->
                    logger.error("更新跟单状态失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.BUSINESS_ERROR, e.message))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_UPDATE_FAILED, e.message))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新跟单状态异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_UPDATE_FAILED, e.message))
        }
    }
    
    /**
     * 删除跟单
     */
    @PostMapping("/delete")
    fun deleteCopyTrading(@RequestBody request: CopyTradingDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.copyTradingId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_COPY_TRADING_ID_INVALID))
            }
            
            val result = copyTradingService.deleteCopyTrading(request.copyTradingId)
            result.fold(
                onSuccess = {
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除跟单失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_DELETE_FAILED, e.message))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除跟单异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_DELETE_FAILED, e.message))
        }
    }
    
    /**
     * 查询钱包绑定的模板
     */
    @PostMapping("/account-templates")
    fun getAccountTemplates(@RequestBody request: AccountTemplatesRequest): ResponseEntity<ApiResponse<AccountTemplatesResponse>> {
        return try {
            if (request.accountId <= 0) {
                return ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ACCOUNT_ID_INVALID))
            }
            
            val result = copyTradingService.getAccountTemplates(request.accountId)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询钱包绑定的模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.error(ErrorCode.PARAM_ERROR, e.message))
                        else -> ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_TEMPLATES_FETCH_FAILED, e.message))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询钱包绑定的模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.error(ErrorCode.SERVER_COPY_TRADING_TEMPLATES_FETCH_FAILED, e.message))
        }
    }
}

