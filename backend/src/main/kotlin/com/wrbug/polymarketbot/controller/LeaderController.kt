package com.wrbug.polymarketbot.controller

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.service.LeaderService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * Leader 管理控制器
 */
@RestController
@RequestMapping("/api/copy-trading/leaders")
class LeaderController(
    private val leaderService: LeaderService
) {
    
    private val logger = LoggerFactory.getLogger(LeaderController::class.java)
    
    /**
     * 添加被跟单者
     */
    @PostMapping("/add")
    fun addLeader(@RequestBody request: LeaderAddRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderAddress.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("Leader 地址不能为空"))
            }
            
            val result = leaderService.addLeader(request)
            result.fold(
                onSuccess = { leader ->
                    logger.info("成功添加 Leader: ${leader.id}")
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("添加 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.businessError(e.message ?: "业务逻辑错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("添加 Leader 失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("添加 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("添加 Leader 失败: ${e.message}"))
        }
    }
    
    /**
     * 更新被跟单者
     */
    @PostMapping("/update")
    fun updateLeader(@RequestBody request: LeaderUpdateRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.paramError("Leader ID 无效"))
            }
            
            val result = leaderService.updateLeader(request)
            result.fold(
                onSuccess = { leader ->
                    logger.info("成功更新 Leader: ${leader.id}")
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("更新 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("更新 Leader 失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("更新 Leader 失败: ${e.message}"))
        }
    }
    
    /**
     * 删除被跟单者
     */
    @PostMapping("/delete")
    fun deleteLeader(@RequestBody request: LeaderDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.paramError("Leader ID 无效"))
            }
            
            val result = leaderService.deleteLeader(request.leaderId)
            result.fold(
                onSuccess = {
                    logger.info("成功删除 Leader: ${request.leaderId}")
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除 Leader 失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.businessError(e.message ?: "业务逻辑错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("删除 Leader 失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除 Leader 异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("删除 Leader 失败: ${e.message}"))
        }
    }
    
    /**
     * 查询被跟单者列表
     */
    @PostMapping("/list")
    fun getLeaderList(@RequestBody request: LeaderListRequest): ResponseEntity<ApiResponse<LeaderListResponse>> {
        return try {
            val result = leaderService.getLeaderList(request)
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询 Leader 列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.serverError("查询 Leader 列表失败: ${e.message}"))
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("查询 Leader 列表失败: ${e.message}"))
        }
    }
    
    /**
     * 查询被跟单者详情
     */
    @PostMapping("/detail")
    fun getLeaderDetail(@RequestBody request: LeaderDetailRequest): ResponseEntity<ApiResponse<LeaderDto>> {
        return try {
            if (request.leaderId <= 0) {
                return ResponseEntity.ok(ApiResponse.paramError("Leader ID 无效"))
            }
            
            val result = leaderService.getLeaderDetail(request.leaderId)
            result.fold(
                onSuccess = { leader ->
                    ResponseEntity.ok(ApiResponse.success(leader))
                },
                onFailure = { e ->
                    logger.error("查询 Leader 详情失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("查询 Leader 详情失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询 Leader 详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("查询 Leader 详情失败: ${e.message}"))
        }
    }
}

/**
 * Leader 详情请求
 */
data class LeaderDetailRequest(
    val leaderId: Long
)

