package com.wrbug.polymarketbot.service

import com.wrbug.polymarketbot.dto.*
import com.wrbug.polymarketbot.entity.CopyTradingTemplate
import com.wrbug.polymarketbot.repository.CopyTradingRepository
import com.wrbug.polymarketbot.repository.CopyTradingTemplateRepository
import com.wrbug.polymarketbot.util.toSafeBigDecimal
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

/**
 * 跟单模板管理服务
 */
@Service
class CopyTradingTemplateService(
    private val templateRepository: CopyTradingTemplateRepository,
    private val copyTradingRepository: CopyTradingRepository
) {
    
    private val logger = LoggerFactory.getLogger(CopyTradingTemplateService::class.java)
    
    /**
     * 创建模板
     */
    @Transactional
    fun createTemplate(request: TemplateCreateRequest): Result<TemplateDto> {
        return try {
            // 1. 验证模板名称
            if (request.templateName.isBlank()) {
                return Result.failure(IllegalArgumentException("模板名称不能为空"))
            }
            
            // 2. 检查模板名称是否已存在
            if (templateRepository.existsByTemplateName(request.templateName)) {
                return Result.failure(IllegalArgumentException("模板名称已存在"))
            }
            
            // 3. 验证 copyMode
            if (request.copyMode !in listOf("RATIO", "FIXED")) {
                return Result.failure(IllegalArgumentException("copyMode 必须是 RATIO 或 FIXED"))
            }
            
            // 4. 创建模板
            val template = CopyTradingTemplate(
                templateName = request.templateName,
                copyMode = request.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: BigDecimal.ONE,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal(),
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: "1000".toSafeBigDecimal(),
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: "1".toSafeBigDecimal(),
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: "10000".toSafeBigDecimal(),
                maxDailyOrders = request.maxDailyOrders ?: 100,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: "5".toSafeBigDecimal(),
                delaySeconds = request.delaySeconds ?: 0,
                pollIntervalSeconds = request.pollIntervalSeconds ?: 5,
                useWebSocket = request.useWebSocket ?: true,
                websocketReconnectInterval = request.websocketReconnectInterval ?: 5000,
                websocketMaxRetries = request.websocketMaxRetries ?: 10,
                supportSell = request.supportSell ?: true
            )
            
            val saved = templateRepository.save(template)
            logger.info("成功创建模板: ${saved.id}, ${saved.templateName}")
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("创建模板失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 更新模板
     */
    @Transactional
    fun updateTemplate(request: TemplateUpdateRequest): Result<TemplateDto> {
        return try {
            val template = templateRepository.findById(request.templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            
            // 如果提供了模板名称，验证名称唯一性
            if (request.templateName != null) {
                if (request.templateName.isBlank()) {
                    return Result.failure(IllegalArgumentException("模板名称不能为空"))
                }
                // 如果新名称与当前名称不同，检查是否已存在
                if (request.templateName != template.templateName) {
                    if (templateRepository.existsByTemplateName(request.templateName)) {
                        return Result.failure(IllegalArgumentException("模板名称已存在"))
                    }
                }
            }
            
            // 验证 copyMode
            if (request.copyMode != null && request.copyMode !in listOf("RATIO", "FIXED")) {
                return Result.failure(IllegalArgumentException("copyMode 必须是 RATIO 或 FIXED"))
            }
            
            val updated = template.copy(
                templateName = request.templateName ?: template.templateName,
                copyMode = request.copyMode ?: template.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: template.copyRatio,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal() ?: template.fixedAmount,
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: template.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: template.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: template.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: template.maxDailyOrders,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: template.priceTolerance,
                delaySeconds = request.delaySeconds ?: template.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: template.pollIntervalSeconds,
                useWebSocket = request.useWebSocket ?: template.useWebSocket,
                websocketReconnectInterval = request.websocketReconnectInterval ?: template.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: template.websocketMaxRetries,
                supportSell = request.supportSell ?: template.supportSell,
                updatedAt = System.currentTimeMillis()
            )
            
            val saved = templateRepository.save(updated)
            logger.info("成功更新模板: ${saved.id}")
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("更新模板失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 删除模板
     */
    @Transactional
    fun deleteTemplate(templateId: Long): Result<Unit> {
        return try {
            val template = templateRepository.findById(templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            
            // 检查是否有跟单正在使用该模板
            val useCount = copyTradingRepository.countByTemplateId(templateId)
            if (useCount > 0) {
                return Result.failure(IllegalStateException("该模板还有 $useCount 个跟单关系在使用，请先删除跟单关系"))
            }
            
            templateRepository.delete(template)
            logger.info("成功删除模板: $templateId")
            
            Result.success(Unit)
        } catch (e: Exception) {
            logger.error("删除模板失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 复制模板
     */
    @Transactional
    fun copyTemplate(request: TemplateCopyRequest): Result<TemplateDto> {
        return try {
            val sourceTemplate = templateRepository.findById(request.templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("源模板不存在"))
            
            // 检查新模板名称是否已存在
            if (templateRepository.existsByTemplateName(request.templateName)) {
                return Result.failure(IllegalArgumentException("模板名称已存在"))
            }
            
            // 创建新模板
            val newTemplate = CopyTradingTemplate(
                templateName = request.templateName,
                copyMode = request.copyMode ?: sourceTemplate.copyMode,
                copyRatio = request.copyRatio?.toSafeBigDecimal() ?: sourceTemplate.copyRatio,
                fixedAmount = request.fixedAmount?.toSafeBigDecimal() ?: sourceTemplate.fixedAmount,
                maxOrderSize = request.maxOrderSize?.toSafeBigDecimal() ?: sourceTemplate.maxOrderSize,
                minOrderSize = request.minOrderSize?.toSafeBigDecimal() ?: sourceTemplate.minOrderSize,
                maxDailyLoss = request.maxDailyLoss?.toSafeBigDecimal() ?: sourceTemplate.maxDailyLoss,
                maxDailyOrders = request.maxDailyOrders ?: sourceTemplate.maxDailyOrders,
                priceTolerance = request.priceTolerance?.toSafeBigDecimal() ?: sourceTemplate.priceTolerance,
                delaySeconds = request.delaySeconds ?: sourceTemplate.delaySeconds,
                pollIntervalSeconds = request.pollIntervalSeconds ?: sourceTemplate.pollIntervalSeconds,
                useWebSocket = request.useWebSocket ?: sourceTemplate.useWebSocket,
                websocketReconnectInterval = request.websocketReconnectInterval ?: sourceTemplate.websocketReconnectInterval,
                websocketMaxRetries = request.websocketMaxRetries ?: sourceTemplate.websocketMaxRetries,
                supportSell = request.supportSell ?: sourceTemplate.supportSell
            )
            
            val saved = templateRepository.save(newTemplate)
            logger.info("成功复制模板: ${sourceTemplate.id} -> ${saved.id}")
            
            Result.success(toDto(saved))
        } catch (e: Exception) {
            logger.error("复制模板失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询模板列表
     */
    fun getTemplateList(): Result<TemplateListResponse> {
        return try {
            val templates = templateRepository.findAllByOrderByCreatedAtDesc()
            val templateDtos = templates.map { template ->
                val useCount = copyTradingRepository.countByTemplateId(template.id!!)
                toDto(template, useCount)
            }
            
            Result.success(
                TemplateListResponse(
                    list = templateDtos,
                    total = templateDtos.size.toLong()
                )
            )
        } catch (e: Exception) {
            logger.error("查询模板列表失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 查询模板详情
     */
    fun getTemplateDetail(templateId: Long): Result<TemplateDto> {
        return try {
            val template = templateRepository.findById(templateId).orElse(null)
                ?: return Result.failure(IllegalArgumentException("模板不存在"))
            
            val useCount = copyTradingRepository.countByTemplateId(templateId)
            Result.success(toDto(template, useCount))
        } catch (e: Exception) {
            logger.error("查询模板详情失败", e)
            Result.failure(e)
        }
    }
    
    /**
     * 转换为 DTO
     */
    private fun toDto(template: CopyTradingTemplate, useCount: Long = 0): TemplateDto {
        return TemplateDto(
            id = template.id!!,
            templateName = template.templateName,
            copyMode = template.copyMode,
            copyRatio = template.copyRatio.toPlainString(),
            fixedAmount = template.fixedAmount?.toPlainString(),
            maxOrderSize = template.maxOrderSize.toPlainString(),
            minOrderSize = template.minOrderSize.toPlainString(),
            maxDailyLoss = template.maxDailyLoss.toPlainString(),
            maxDailyOrders = template.maxDailyOrders,
            priceTolerance = template.priceTolerance.toPlainString(),
            delaySeconds = template.delaySeconds,
            pollIntervalSeconds = template.pollIntervalSeconds,
            useWebSocket = template.useWebSocket,
            websocketReconnectInterval = template.websocketReconnectInterval,
            websocketMaxRetries = template.websocketMaxRetries,
            supportSell = template.supportSell,
            useCount = useCount,
            createdAt = template.createdAt,
            updatedAt = template.updatedAt
        )
    }
}

