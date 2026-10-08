package org.autojs.autojs.mcp

import android.util.Base64
import org.autojs.autojs.Pref
import java.security.SecureRandom

/**
 * MCP 服务的全部可调参数。
 *
 * 用户可配置项走 [Pref]（SharedPreferences），其余为内部常量，集中在此便于统一调整。
 */
object McpConfig {

    /** MCP 端点路径。规范要求单个 POST 端点 */
    const val PATH = "/mcp"

    const val DEFAULT_PORT = 9318

    /** 仅本机可访问时的绑定地址 */
    const val LOOPBACK_HOST = "127.0.0.1"

    /** 允许局域网访问时的绑定地址 */
    const val LAN_HOST = "0.0.0.0"

    /** 请求体上限，防止超大 body 撑爆内存 */
    const val MAX_REQUEST_BYTES = 8 * 1024 * 1024

    /** 返回图片的默认缩放宽度（0 表示不缩放） */
    const val DEFAULT_CAPTURE_MAX_WIDTH = 1080

    /** 单张返回图片的字节上限；超过则要求客户端降低画质，避免撞客户端消息上限 */
    const val MAX_IMAGE_BYTES = 4 * 1024 * 1024

    /**
     * 手势时长硬上限。
     *
     * GlobalActionAutomator 的手势走 blockGet()，客户端断开也无法中断，必须限制在途时间。
     */
    const val MAX_GESTURE_MS = 10_000L

    /** 列表类结果的缓存提示，避免客户端反复拉取工具表 */
    const val TOOLS_TTL_MS = 60_000L
    const val DISCOVER_TTL_MS = 3_600_000L

    const val SHELL_TIMEOUT_MS = 15_000L
    const val CAPTURE_TIMEOUT_MS = 60_000L
    const val OCR_TIMEOUT_MS = 120_000L
    const val MATCH_TIMEOUT_MS = 60_000L

    val port: Int get() = Pref.getMcpPort()
    val allowLan: Boolean get() = Pref.isMcpAllowLan()
    val tokenRequired: Boolean get() = Pref.isMcpTokenRequired()
    val auditLogEnabled: Boolean get() = Pref.isMcpAuditLogEnabled()

    val host: String get() = if (allowLan) LAN_HOST else LOOPBACK_HOST

    /**
     * 当前 Token。未生成过则即时生成并持久化，保证服务启动后用户即可复制。
     */
    fun token(): String {
        val existing = Pref.getMcpToken()
        if (existing.isNotEmpty()) {
            return existing
        }
        val created = newToken()
        Pref.setMcpToken(created)
        return created
    }

    /** 重新生成 Token，旧 Token 立即失效 */
    fun regenerateToken(): String {
        val created = newToken()
        Pref.setMcpToken(created)
        return created
    }

    private fun newToken(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.URL_SAFE)
    }
}
