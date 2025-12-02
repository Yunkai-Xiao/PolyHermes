package com.wrbug.polymarketbot.dto

import com.wrbug.polymarketbot.enums.ErrorCode

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
         * 创建失败响应（使用 ErrorCode 枚举）
         */
        fun <T> error(errorCode: ErrorCode, customMsg: String? = null): ApiResponse<T> {
            return ApiResponse(
                code = errorCode.code,
                data = null,
                msg = customMsg ?: errorCode.message
            )
        }
        
        /**
         * 创建失败响应（使用错误码和自定义消息）
         */
        fun <T> error(code: Int, msg: String): ApiResponse<T> {
            return ApiResponse(code = code, data = null, msg = msg)
        }
        
        /**
         * 创建参数错误响应（兼容旧代码，建议使用 error(ErrorCode)）
         */
        @Deprecated("使用 error(ErrorCode.PARAM_ERROR, msg) 替代", ReplaceWith("error(ErrorCode.PARAM_ERROR, msg)"))
        fun <T> paramError(msg: String): ApiResponse<T> {
            return error(ErrorCode.PARAM_ERROR, msg)
        }
        
        /**
         * 创建认证错误响应（兼容旧代码，建议使用 error(ErrorCode)）
         */
        @Deprecated("使用 error(ErrorCode.AUTH_ERROR, msg) 替代", ReplaceWith("error(ErrorCode.AUTH_ERROR, msg)"))
        fun <T> authError(msg: String): ApiResponse<T> {
            return error(ErrorCode.AUTH_ERROR, msg)
        }
        
        /**
         * 创建资源不存在响应（兼容旧代码，建议使用 error(ErrorCode)）
         */
        @Deprecated("使用 error(ErrorCode.NOT_FOUND, msg) 替代", ReplaceWith("error(ErrorCode.NOT_FOUND, msg)"))
        fun <T> notFound(msg: String): ApiResponse<T> {
            return error(ErrorCode.NOT_FOUND, msg)
        }
        
        /**
         * 创建业务逻辑错误响应（兼容旧代码，建议使用 error(ErrorCode)）
         */
        @Deprecated("使用 error(ErrorCode.BUSINESS_ERROR, msg) 替代", ReplaceWith("error(ErrorCode.BUSINESS_ERROR, msg)"))
        fun <T> businessError(msg: String): ApiResponse<T> {
            return error(ErrorCode.BUSINESS_ERROR, msg)
        }
        
        /**
         * 创建服务器内部错误响应（兼容旧代码，建议使用 error(ErrorCode)）
         */
        @Deprecated("使用 error(ErrorCode.SERVER_ERROR, msg) 替代", ReplaceWith("error(ErrorCode.SERVER_ERROR, msg)"))
        fun <T> serverError(msg: String): ApiResponse<T> {
            return error(ErrorCode.SERVER_ERROR, msg)
        }
    }
}

