package org.autojs.autojs.mcp

import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

/**
 * MCP 端点的准入校验：Origin / Host / Token。
 *
 * 三个 check 都返回「拒绝原因」（null 表示放行），便于调用方统一映射成 HTTP 状态码并记录审计日志。
 */
object McpSecurity {

    private const val BEARER_PREFIX = "bearer "

    /**
     * Origin 校验（规范强制）。
     *
     * 缺失 Origin 时放行：非浏览器客户端（Claude Desktop、MCP Inspector、curl）通常不发这个头，
     * 强制要求会把合法客户端全部挡在门外。该头的作用是防 DNS rebinding，只对浏览器有意义。
     */
    fun checkOrigin(origin: String?, port: Int, allowLan: Boolean): String? {
        if (origin.isNullOrBlank()) {
            return null
        }
        val normalized = origin.trim().trimEnd('/').lowercase()
        return if (allowedOriginHosts(port, allowLan).contains(normalized)) {
            null
        } else {
            "Origin not allowed: $origin"
        }
    }

    /**
     * Host 校验。
     *
     * 这是 DNS rebinding 的真正防线：恶意页面发起的请求会带自己的 Origin 与 Host，
     * 只校验其中一个都可能被绕过。
     */
    fun checkHost(host: String?, allowLan: Boolean): String? {
        if (host.isNullOrBlank()) {
            return null
        }
        val name = host.substringBefore(':').trim().lowercase()
        return if (allowedHostNames(allowLan).contains(name)) null else "Host not allowed: $host"
    }

    /** Bearer Token 校验，使用常量时间比较避免时序侧信道 */
    fun checkToken(authorization: String?, required: Boolean, expectedToken: String): String? {
        if (!required) {
            return null
        }
        if (authorization.isNullOrBlank()) {
            return "Missing Authorization header"
        }
        if (!authorization.lowercase().startsWith(BEARER_PREFIX)) {
            return "Authorization header must use the Bearer scheme"
        }
        val presented = authorization.substring(BEARER_PREFIX.length).trim()
        return if (MessageDigest.isEqual(presented.toByteArray(), expectedToken.toByteArray())) {
            null
        } else {
            "Invalid token"
        }
    }

    /** 局域网模式必须启用 Token —— 否则同网段任何人都能操控这台设备 */
    fun lanModeViolation(allowLan: Boolean, tokenRequired: Boolean): String? =
        if (allowLan && !tokenRequired) "局域网模式必须启用鉴权（Token）" else null

    /** 本机各网卡的 IPv4 地址，用于局域网模式下的白名单 */
    fun localIpv4Addresses(): List<String> {
        val result = ArrayList<String>()
        val interfaces = runCatching { NetworkInterface.getNetworkInterfaces() }.getOrNull()
            ?: return result
        runCatching {
            interfaces.toList().forEach { nif ->
                if (!nif.isUp || nif.isLoopback) {
                    return@forEach
                }
                nif.inetAddresses.toList().forEach { address ->
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        address.hostAddress?.let { result.add(it) }
                    }
                }
            }
        }
        return result
    }

    private fun allowedOriginHosts(port: Int, allowLan: Boolean): Set<String> {
        val result = HashSet<String>()
        allowedHostNames(allowLan).forEach { host ->
            result.add("http://$host")
            result.add("http://$host:$port")
        }
        return result
    }

    private fun allowedHostNames(allowLan: Boolean): Set<String> {
        val names = HashSet<String>()
        names.add("localhost")
        names.add("127.0.0.1")
        if (allowLan) {
            names.addAll(localIpv4Addresses())
        }
        return names
    }
}
