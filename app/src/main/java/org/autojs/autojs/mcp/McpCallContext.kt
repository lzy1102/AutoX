package org.autojs.autojs.mcp

import com.google.gson.JsonObject

/**
 * 单次请求的上下文。
 *
 * 2026-07-28 起协议无状态：客户端的协议版本、身份、能力都随每个请求携带，
 * 因此这里没有「连接上下文」，每次调用都是独立快照。
 */
data class McpCallContext(
    /** 请求声明的协议版本；null 表示未带版本头的旧客户端 */
    val requestedVersion: String?,
    val clientName: String? = null,
    val clientVersion: String? = null,
    val logLevel: String? = null,
) {

    /** 是否走旧握手（initialize）语义 */
    val isLegacy: Boolean get() = requestedVersion == null

    /** 本次请求实际生效的版本 */
    val effectiveVersion: String
        get() = requestedVersion ?: McpProtocol.HEADERLESS_LEGACY_VERSION

    companion object {

        /**
         * 从请求体 `params._meta` 与 HTTP 头解析上下文。
         *
         * [headerVersion] 为 null 时按旧客户端处理——规范允许服务端把不带版本头的请求视为 2025-03-26。
         */
        fun from(params: JsonObject?, headerVersion: String?): McpCallContext {
            val meta = params?.get("_meta")?.takeIf { it.isJsonObject }?.asJsonObject
            val clientInfo = meta?.get(McpProtocol.META_CLIENT_INFO)
                ?.takeIf { it.isJsonObject }?.asJsonObject
            val logLevel = meta?.get(McpProtocol.META_LOG_LEVEL)
                ?.takeIf { it.isJsonPrimitive }?.asString
            return McpCallContext(
                // 头缺失时视为旧客户端；即使 body 里带了 _meta 版本也不据此升级语义
                requestedVersion = headerVersion,
                clientName = clientInfo?.get("name")?.takeIf { it.isJsonPrimitive }?.asString,
                clientVersion = clientInfo?.get("version")?.takeIf { it.isJsonPrimitive }?.asString,
                logLevel = logLevel,
            )
        }
    }
}
