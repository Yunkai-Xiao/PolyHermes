package com.wrbug.polymarketbot.entity

import jakarta.persistence.*
import com.wrbug.polymarketbot.util.CategoryValidator

/**
 * 被跟单者（Leader）实体
 */
@Entity
@Table(name = "copy_trading_leaders")
data class Leader(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,
    
    @Column(name = "leader_address", unique = true, nullable = false, length = 42)
    val leaderAddress: String,  // 钱包地址
    
    @Column(name = "leader_name", length = 100)
    val leaderName: String? = null,
    
    @Column(name = "category", length = 20)
    val category: String? = null,  // sports 或 crypto，null 表示不筛选
    
    @Column(name = "created_at", nullable = false)
    val createdAt: Long = System.currentTimeMillis(),
    
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Long = System.currentTimeMillis()
) {
    init {
        // 验证分类
        if (category != null) {
            CategoryValidator.validate(category)
        }
    }
}

