package com.wrbug.polymarketbot.controller

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.service.CopyTradingTemplateService
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/**
 * 跟单模板管理控制器
 */
@RestController
@RequestMapping("/api/copy-trading/templates")
class CopyTradingTemplateController(
    private val templateService: CopyTradingTemplateService
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingTemplateController::class.java)
    
    /**
     * 创建模板
     */
    @PostMapping("/create")
    fun createTemplate(@RequestBody request: TemplateCreateRequest): ResponseEntity<ApiResponse<TemplateDto>> {
        return try {
            if (request.templateName.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("模板名称不能为空"))
            }
            
            val result = templateService.createTemplate(request)
            result.fold(
                onSuccess = { template ->
                    logger.info("成功创建模板: ${template.id}")
                    ResponseEntity.ok(ApiResponse.success(template))
                },
                onFailure = { e ->
                    logger.error("创建模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("创建模板失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("创建模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("创建模板失败: ${e.message}"))
        }
    }
    
    /**
     * 更新模板
     */
    @PostMapping("/update")
    fun updateTemplate(@RequestBody request: TemplateUpdateRequest): ResponseEntity<ApiResponse<TemplateDto>> {
        return try {
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.paramError("模板 ID 无效"))
            }
            
            val result = templateService.updateTemplate(request)
            result.fold(
                onSuccess = { template ->
                    logger.info("成功更新模板: ${template.id}")
                    ResponseEntity.ok(ApiResponse.success(template))
                },
                onFailure = { e ->
                    logger.error("更新模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("更新模板失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("更新模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("更新模板失败: ${e.message}"))
        }
    }
    
    /**
     * 删除模板
     */
    @PostMapping("/delete")
    fun deleteTemplate(@RequestBody request: TemplateDeleteRequest): ResponseEntity<ApiResponse<Unit>> {
        return try {
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.paramError("模板 ID 无效"))
            }
            
            val result = templateService.deleteTemplate(request.templateId)
            result.fold(
                onSuccess = {
                    logger.info("成功删除模板: ${request.templateId}")
                    ResponseEntity.ok(ApiResponse.success(Unit))
                },
                onFailure = { e ->
                    logger.error("删除模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        is IllegalStateException -> ResponseEntity.ok(ApiResponse.businessError(e.message ?: "业务逻辑错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("删除模板失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("删除模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("删除模板失败: ${e.message}"))
        }
    }
    
    /**
     * 复制模板
     */
    @PostMapping("/copy")
    fun copyTemplate(@RequestBody request: TemplateCopyRequest): ResponseEntity<ApiResponse<TemplateDto>> {
        return try {
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.paramError("模板 ID 无效"))
            }
            if (request.templateName.isBlank()) {
                return ResponseEntity.ok(ApiResponse.paramError("新模板名称不能为空"))
            }
            
            val result = templateService.copyTemplate(request)
            result.fold(
                onSuccess = { template ->
                    logger.info("成功复制模板: ${template.id}")
                    ResponseEntity.ok(ApiResponse.success(template))
                },
                onFailure = { e ->
                    logger.error("复制模板失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("复制模板失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("复制模板异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("复制模板失败: ${e.message}"))
        }
    }
    
    /**
     * 查询模板列表
     */
    @PostMapping("/list")
    fun getTemplateList(): ResponseEntity<ApiResponse<TemplateListResponse>> {
        return try {
            val result = templateService.getTemplateList()
            result.fold(
                onSuccess = { response ->
                    ResponseEntity.ok(ApiResponse.success(response))
                },
                onFailure = { e ->
                    logger.error("查询模板列表失败: ${e.message}", e)
                    ResponseEntity.ok(ApiResponse.serverError("查询模板列表失败: ${e.message}"))
                }
            )
        } catch (e: Exception) {
            logger.error("查询模板列表异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("查询模板列表失败: ${e.message}"))
        }
    }
    
    /**
     * 查询模板详情
     */
    @PostMapping("/detail")
    fun getTemplateDetail(@RequestBody request: TemplateDetailRequest): ResponseEntity<ApiResponse<TemplateDto>> {
        return try {
            if (request.templateId <= 0) {
                return ResponseEntity.ok(ApiResponse.paramError("模板 ID 无效"))
            }
            
            val result = templateService.getTemplateDetail(request.templateId)
            result.fold(
                onSuccess = { template ->
                    ResponseEntity.ok(ApiResponse.success(template))
                },
                onFailure = { e ->
                    logger.error("查询模板详情失败: ${e.message}", e)
                    when (e) {
                        is IllegalArgumentException -> ResponseEntity.ok(ApiResponse.paramError(e.message ?: "参数错误"))
                        else -> ResponseEntity.ok(ApiResponse.serverError("查询模板详情失败: ${e.message}"))
                    }
                }
            )
        } catch (e: Exception) {
            logger.error("查询模板详情异常: ${e.message}", e)
            ResponseEntity.ok(ApiResponse.serverError("查询模板详情失败: ${e.message}"))
        }
    }
}

