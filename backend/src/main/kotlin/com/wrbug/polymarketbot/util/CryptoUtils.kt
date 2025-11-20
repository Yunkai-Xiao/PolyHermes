package com.wrbug.polymarketbot.util

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.*
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * 加密工具类
 * 用于加密存储私钥和 API Key
 */
@Component
class CryptoUtils {
    
    private val logger = LoggerFactory.getLogger(CryptoUtils::class.java)
    
    @Value("\${crypto.secret.key:}")
    private var secretKey: String = ""
    
    private val algorithm = "AES"
    private val transformation = "AES"
    
    /**
     * 获取密钥字节数组
     * 使用 SHA-256 哈希从任意长度的密钥生成固定 32 字节的密钥（AES-256）
     */
    private fun getKeyBytes(): ByteArray {
        val rawKey = if (secretKey.isEmpty()) {
            logger.warn("未配置加密密钥，使用默认密钥（仅用于开发环境）")
            "default-secret-key-32-bytes-long!!"
        } else {
            secretKey
        }
        
        // 将原始密钥转换为字节数组
        val keyBytes = rawKey.toByteArray(StandardCharsets.UTF_8)
        
        // 使用 SHA-256 哈希生成固定 32 字节的密钥（AES-256）
        val messageDigest = java.security.MessageDigest.getInstance("SHA-256")
        return messageDigest.digest(keyBytes)
    }
    
    /**
     * 加密字符串
     */
    fun encrypt(plainText: String): String {
        return try {
            val keyBytes = getKeyBytes()
            val key = SecretKeySpec(keyBytes, algorithm)
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.doFinal(plainText.toByteArray(StandardCharsets.UTF_8))
            Base64.getEncoder().encodeToString(encrypted)
        } catch (e: Exception) {
            logger.error("加密失败", e)
            throw RuntimeException("加密失败: ${e.message}", e)
        }
    }
    
    /**
     * 解密字符串
     */
    fun decrypt(encryptedText: String): String {
        return try {
            val keyBytes = getKeyBytes()
            val key = SecretKeySpec(keyBytes, algorithm)
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.DECRYPT_MODE, key)
            val decrypted = cipher.doFinal(Base64.getDecoder().decode(encryptedText))
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            logger.error("解密失败", e)
            throw RuntimeException("解密失败: ${e.message}", e)
        }
    }
}

