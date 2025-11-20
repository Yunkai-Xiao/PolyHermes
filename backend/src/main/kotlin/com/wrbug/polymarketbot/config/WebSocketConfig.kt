package com.wrbug.polymarketbot.config

import com.wrbug.polymarketbot.websocket.PolymarketWebSocketHandler
import org.springframework.context.annotation.Configuration
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry

/**
 * WebSocket 配置类
 * 用于配置 WebSocket 端点
 */
@Configuration
@EnableWebSocket
class WebSocketConfig(
    private val polymarketWebSocketHandler: PolymarketWebSocketHandler
) : WebSocketConfigurer {
    
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(polymarketWebSocketHandler, "/ws/polymarket")
            .setAllowedOrigins("*")  // 生产环境应该配置具体的域名
    }
}

