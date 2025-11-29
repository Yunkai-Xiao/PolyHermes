package com.wrbug.polymarketbot.util

import org.bouncycastle.crypto.digests.KeccakDigest
import java.math.BigInteger

/**
 * Ethereum 工具类
 * 用于计算函数签名、编码参数等
 */
object EthereumUtils {
    
    // Polymarket 合约地址（Polygon 主网）
    private val COLLATERAL_TOKEN_ADDRESS = "0x2791Bca1f2de4661ED88A30C99A7a9449Aa84174" // USDC
    private val CONDITIONAL_TOKENS_ADDRESS = "0x4D97DCd97eC945f40cF65F87097ACe5EA0476045" // ConditionalTokens
    
    /**
     * 计算函数选择器（前4个字节）
     * @param functionSignature 函数签名，例如 "computeProxyAddress(address)"
     * @return 函数选择器，例如 "0x12345678"
     */
    fun getFunctionSelector(functionSignature: String): String {
        val hash = keccak256(functionSignature.toByteArray())
        return "0x" + hash.substring(0, 8)
    }
    
    /**
     * 编码地址参数（32字节，左对齐）
     * @param address 地址，例如 "0x1234..."
     * @return 编码后的地址，64个十六进制字符
     */
    fun encodeAddress(address: String): String {
        val cleanAddress = address.removePrefix("0x").lowercase()
        return cleanAddress.padStart(64, '0')
    }
    
    /**
     * 编码 uint256 参数
     * @param value 数值
     * @return 编码后的值，64个十六进制字符
     */
    fun encodeUint256(value: BigInteger): String {
        return value.toString(16).padStart(64, '0')
    }
    
    /**
     * 编码 bytes32 参数
     * @param value 32字节的十六进制字符串（带或不带0x前缀）
     * @return 编码后的值，64个十六进制字符
     */
    fun encodeBytes32(value: String): String {
        val cleanValue = value.removePrefix("0x")
        if (cleanValue.length != 64) {
            throw IllegalArgumentException("bytes32 值必须是64个十六进制字符")
        }
        return cleanValue.lowercase()
    }
    
    /**
     * 从合约调用结果中解析地址
     * @param hexResult 十六进制结果
     * @return 地址字符串
     */
    fun decodeAddress(hexResult: String): String {
        val cleanHex = hexResult.removePrefix("0x")
        // 地址是最后20字节（40个十六进制字符）
        val addressHex = cleanHex.takeLast(40)
        return "0x$addressHex"
    }
    
    /**
     * 从合约调用结果中解析 uint256
     * @param hexResult 十六进制结果
     * @return BigInteger 值
     */
    fun decodeUint256(hexResult: String): BigInteger {
        val cleanHex = hexResult.removePrefix("0x")
        return BigInteger(cleanHex, 16)
    }
    
    /**
     * 计算 Keccak-256 哈希（Ethereum 标准）
     * 使用 BouncyCastle 库实现真正的 Keccak-256
     */
    private fun keccak256(data: ByteArray): String {
        val digest = KeccakDigest(256)
        digest.update(data, 0, data.size)
        val hash = ByteArray(digest.digestSize)
        digest.doFinal(hash, 0)
        return hash.joinToString("") { "%02x".format(it) }
    }
}

