package com.wrbug.polymarketbot.websocket

import com.fasterxml.jackson.databind.ObjectMapper
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Polymarket WebSocket 客户端
 * 用于连接到 Polymarket RTDS
 */
class PolymarketWebSocketClient(
    serverUri: URI,
    private val objectMapper: ObjectMapper,
    private val sessionId: String,
    private val onMessage: (String) -> Unit
) : WebSocketClient(serverUri) {
    
    private val logger = LoggerFactory.getLogger(PolymarketWebSocketClient::class.java)
    
    override fun onOpen(handshakedata: ServerHandshake?) {
        logger.info("已成功连接到 Polymarket RTDS: $sessionId")
    }
    
    override fun onMessage(message: String?) {
        if (message != null) {
            logger.debug("收到 Polymarket 消息: $sessionId, $message")
            onMessage(message)
        }
    }
    
    override fun onClose(code: Int, reason: String?, remote: Boolean) {
        logger.info("Polymarket 连接关闭: $sessionId, code: $code, reason: $reason, remote: $remote")
    }
    
    /**
     * 关闭连接
     */
    fun closeConnection() {
        if (isOpen) {
            try {
                closeBlocking()
            } catch (e: Exception) {
                logger.error("关闭连接失败: $sessionId, ${e.message}", e)
            }
        }
    }
    
    override fun onError(ex: Exception?) {
        logger.error("Polymarket WebSocket 错误: $sessionId, ${ex?.message}", ex)
    }
    
    /**
     * 发送消息到 Polymarket
     */
    fun sendMessage(message: String) {
        if (isOpen) {
            try {
                send(message)
            } catch (e: Exception) {
                logger.error("发送消息失败: $sessionId, ${e.message}", e)
                throw e
            }
        } else {
            logger.warn("WebSocket 未连接，无法发送消息: $sessionId")
        }
    }
    
    /**
     * 检查连接状态
     */
    fun isConnected(): Boolean {
        return isOpen
    }
}

