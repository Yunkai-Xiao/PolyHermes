package com.wrbug.polymarketbot.dto

/**
 * 统一API响应格式
 * @param code 响应码，0表示成功，非0表示失败
 * @param data 响应数据，可以是任意类型（对象、数组、字符串、数字等）
 * @param msg 响应消息，成功时通常为空，失败时包含错误提示
 */
data class ApiResponse<T>(
    val code: Int,
    val data: T?,
    val msg: String
) {
    companion object {
        /**
         * 创建成功响应
         */
        fun <T> success(data: T?): ApiResponse<T> {
            return ApiResponse(code = 0, data = data, msg = "")
        }
        
        /**
         * 创建失败响应
         */
        fun <T> error(code: Int, msg: String): ApiResponse<T> {
            return ApiResponse(code = code, data = null, msg = msg)
        }
        
        /**
         * 创建参数错误响应
         */
        fun <T> paramError(msg: String): ApiResponse<T> {
            return ApiResponse(code = 1001, data = null, msg = msg)
        }
        
        /**
         * 创建认证错误响应
         */
        fun <T> authError(msg: String): ApiResponse<T> {
            return ApiResponse(code = 2001, data = null, msg = msg)
        }
        
        /**
         * 创建资源不存在响应
         */
        fun <T> notFound(msg: String): ApiResponse<T> {
            return ApiResponse(code = 3001, data = null, msg = msg)
        }
        
        /**
         * 创建业务逻辑错误响应
         */
        fun <T> businessError(msg: String): ApiResponse<T> {
            return ApiResponse(code = 4001, data = null, msg = msg)
        }
        
        /**
         * 创建服务器内部错误响应
         */
        fun <T> serverError(msg: String): ApiResponse<T> {
            return ApiResponse(code = 5001, data = null, msg = msg)
        }
    }
}

