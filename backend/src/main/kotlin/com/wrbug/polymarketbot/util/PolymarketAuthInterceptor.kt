package com.wrbug.polymarketbot.util

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.io.IOException
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64

/**
 * Polymarket API 认证拦截器
 * 实现 L2 认证（使用 API Key、Secret、Passphrase）
 * 
 * 认证方式：
 * 1. 生成时间戳（秒）
 * 2. 使用 Secret 对 (timestamp + method + path + body) 进行 HMAC-SHA256 签名
 * 3. 在请求头中添加：
 *    - X-API-KEY: API Key
 *    - X-API-SIGN: Base64 编码的签名
 *    - X-API-TIMESTAMP: 时间戳
 *    - X-API-PASSPHRASE: Passphrase
 */
class PolymarketAuthInterceptor(
    private val apiKey: String,
    private val apiSecret: String,
    private val apiPassphrase: String
) : Interceptor {
    
    @Throws(IOException::class)
    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()
        
        // 生成时间戳（秒）
        val timestamp = Instant.now().epochSecond.toString()
        
        // 构建签名字符串: timestamp + method + path + body
        val method = originalRequest.method
        val path = originalRequest.url.encodedPath + if (originalRequest.url.query != null) "?${originalRequest.url.query}" else ""
        
        // 读取请求体（如果存在）
        val body = originalRequest.body?.let { requestBody ->
            val buffer = Buffer()
            requestBody.writeTo(buffer)
            buffer.readUtf8()
        } ?: ""
        
        val signString = "$timestamp$method$path$body"
        
        // 使用 HMAC-SHA256 生成签名
        val signature = generateSignature(signString, apiSecret)
        
        // 构建新的请求，添加认证头
        val newRequest = originalRequest.newBuilder()
            .header("X-API-KEY", apiKey)
            .header("X-API-SIGN", signature)
            .header("X-API-TIMESTAMP", timestamp)
            .header("X-API-PASSPHRASE", apiPassphrase)
            .build()
        
        return chain.proceed(newRequest)
    }
    
    /**
     * 使用 HMAC-SHA256 生成签名
     */
    private fun generateSignature(message: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        val secretKeySpec = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
        mac.init(secretKeySpec)
        val hash = mac.doFinal(message.toByteArray())
        return Base64.getEncoder().encodeToString(hash)
    }
}

