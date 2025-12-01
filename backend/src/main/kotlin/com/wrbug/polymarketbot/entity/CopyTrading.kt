package com.wrbug.polymarketbot.entity

import jakarta.persistence.*

/**
 * 跟单关系实体（钱包-模板关联，多对多关系）
 */
@Entity
@Table(
    name = "copy_trading",
    uniqueConstraints = [
        UniqueConstraint(columnNames = ["account_id", "template_id", "leader_id"])
    ]
)
data class CopyTrading(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "account_id", nullable = false)
    val accountId: Long,  // 钱包账户ID
    
    @Column(name = "template_id", nullable = false)
    val templateId: Long,  // 模板ID
    
    @Column(name = "leader_id", nullable = false)
    val leaderId: Long,  // Leader ID
    
    @Column(name = "enabled", nullable = false)
    val enabled: Boolean = true,  // 是否启用
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
)

