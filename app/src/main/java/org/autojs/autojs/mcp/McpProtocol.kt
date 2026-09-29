package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.autojs.autoxjs.BuildConfig
import java.util.UUID

/**
 * MCP 2026-07-28 协议常量与载荷构造。
 *
 * 所有规范相关的字面量（版本号、_meta 键名、错误码、结果字段名）都收敛在这里，
 * 规范演进时只需改这一处。
 */
object McpProtocol {

    /** 本服务以无状态方式实现的最新规范版本 */
    const val VERSION = "2026-07-28"

    /** 以 initialize 握手方式兼容的旧版本 */
    val LEGACY_VERSIONS = listOf("2025-11-25", "2025-06-18", "2025-03-26", "2024-11-05")

    /** 对外宣告支持的版本，即 server/discover 的 supportedVersions */
    val SUPPORTED_VERSIONS = listOf(VERSION) + LEGACY_VERSIONS

    /** 客户端请求了未知版本时，initialize 回落到这个版本 */
    const val LEGACY_FALLBACK_VERSION = "2025-06-18"

    /**
     * 规范允许服务端把「不带版本头」的请求按 2025-03-26 处理（该版本尚未定义版本头）。
     * 我们据此走旧握手分支，从而兼容旧客户端。
     */
    const val HEADERLESS_LEGACY_VERSION = "2025-03-26"

    // ---------- HTTP 头 ----------
    const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"
    const val HEADER_METHOD = "Mcp-Method"
    const val HEADER_NAME = "Mcp-Name"
    const val HEADER_SESSION_ID = "Mcp-Session-Id"

    // ---------- _meta 键 ----------
    const val META_PROTOCOL_VERSION = "io.modelcontextprotocol/protocolVersion"
    const val META_CLIENT_INFO = "io.modelcontextprotocol/clientInfo"
    const val META_CLIENT_CAPABILITIES = "io.modelcontextprotocol/clientCapabilities"
    const val META_SERVER_INFO = "io.modelcontextprotocol/serverInfo"
    const val META_LOG_LEVEL = "io.modelcontextprotocol/logLevel"

    // ---------- result 字段 ----------
    const val RESULT_TYPE = "resultType"
    const val RESULT_TYPE_COMPLETE = "complete"
    const val RESULT_TYPE_INPUT_REQUIRED = "input_required"
    const val CACHE_SCOPE = "cacheScope"
    const val CACHE_SCOPE_PUBLIC = "public"
    const val TTL_MS = "ttlMs"

    // ---------- 方法名 ----------
    const val METHOD_DISCOVER = "server/discover"
    const val METHOD_TOOLS_LIST = "tools/list"
    const val METHOD_TOOLS_CALL = "tools/call"
    const val METHOD_INITIALIZE = "initialize"
    const val METHOD_PING = "ping"
    const val METHOD_LOGGING_SET_LEVEL = "logging/setLevel"
    const val METHOD_RESOURCES_LIST = "resources/list"
    const val METHOD_PROMPTS_LIST = "prompts/list"

    // ---------- JSON-RPC 2.0 标准错误码 ----------
    const val ERROR_PARSE = -32700
    const val ERROR_INVALID_REQUEST = -32600
    const val ERROR_METHOD_NOT_FOUND = -32601
    const val ERROR_INVALID_PARAMS = -32602
    const val ERROR_INTERNAL = -32603

    // ---------- 2026-07-28 定义的错误码 ----------
    /** HeaderMismatchError：头与 body 不一致、或缺必需头时返回，HTTP 400 */
    const val ERROR_HEADER_MISMATCH = -32020

    /** UnsupportedProtocolVersionError：版本不支持时返回，HTTP 400 */
    const val ERROR_UNSUPPORTED_PROTOCOL_VERSION = -32022

    /**
     * 旧握手响应的 Mcp-Session-Id。
     *
     * 旧版本是「会话语义」，但本服务不持有任何会话状态——这里只回显一个进程内固定的值安抚旧客户端，
     * 从不校验、从不存储，因此不破坏无状态语义。
     */
    val LEGACY_SESSION_ID: String by lazy { UUID.randomUUID().toString() }

    val serverName: String = "autox-mcp"

    val serverVersion: String get() = BuildConfig.VERSION_NAME

    val instructions: String = """
        AutoX 设备控制服务，用于在 Android 设备上执行自动化操作（截图、找图找色、OCR、点击滑动、控件操作、应用与脚本管理）。

        使用前建议先调用 device_status 确认环境：
        - 无障碍服务未开启时，所有 ui_* 工具与 input_key 的全局动作不可用；
        - 首次调用 screen_capture 需要在手机上手动确认录屏授权，请提示用户；
        - input_* 的坐标以设备物理像素为单位，可用 screen_capture 的返回尺寸作为参考。
    """.trimIndent()

    fun serverInfo(): JsonObject = JsonObject().apply {
        addProperty("name", serverName)
        addProperty("version", serverVersion)
    }

    /**
     * capabilities。
     *
     * 只声明 tools：不回 logging —— 该能力已在 2026-07-28 中移除，声明了会让新客户端误判。
     */
    fun capabilities(): JsonObject = JsonObject().apply {
        add("tools", JsonObject())
    }

    /** server/discover 的 result */
    fun discoverResult(): JsonObject = JsonObject().apply {
        addProperty(RESULT_TYPE, RESULT_TYPE_COMPLETE)
        add("supportedVersions", JsonArray().apply { SUPPORTED_VERSIONS.forEach { add(it) } })
        add("capabilities", capabilities())
        add("_meta", JsonObject().apply { add(META_SERVER_INFO, serverInfo()) })
        addProperty("instructions", instructions)
        addProperty(TTL_MS, McpConfig.DISCOVER_TTL_MS)
        addProperty(CACHE_SCOPE, CACHE_SCOPE_PUBLIC)
    }

    fun isSupported(version: String?): Boolean =
        version != null && SUPPORTED_VERSIONS.contains(version)

    /** initialize 回显的版本：已知则原样回显，否则回落到旧版基线 */
    fun negotiateLegacyVersion(requested: String?): String =
        if (isSupported(requested)) requested!! else LEGACY_FALLBACK_VERSION
}
