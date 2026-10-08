package org.autojs.autojs.mcp

import android.util.Log
import com.google.gson.JsonObject
import com.stardust.app.GlobalAppContext
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.autojs.autojs.Pref
import org.autojs.autojs.devplugin.JsonUtil
import org.autojs.autojs.devplugin.WebSocketServer
import org.autojs.autojs.mcp.tools.registerC1Tools
import org.autojs.autojs.mcp.tools.registerC3Tools
import org.autojs.autojs.mcp.tools.registerImageTools

/**
 * MCP 服务端（2026-07-28 Streamable HTTP + 旧握手兼容）。
 *
 * 刻意使用**独立的 Netty 引擎与独立端口**，不与 VS Code 调试用的 WebSocket 服务（9317）共用：
 * 那个通道没有鉴权且能执行任意脚本，共用端口会让局域网模式把它一并暴露出去。
 */
object McpServer {

    private const val TAG = "McpServer"

    /** 工具注册表。各工具组在 C1 起向此注册 */
    val registry = McpRegistry()

    private val dispatcher = McpDispatcher(registry)

    init {
        registry.registerC1Tools()
        registry.registerImageTools()
        registry.registerC3Tools()
    }

    private var server: WebSocketServer? = null

    private val _running = MutableStateFlow(false)

    /** 供 UI 观察运行状态 */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    val toolCount: Int get() = registry.size

    val isRunning: Boolean get() = server?.isActive == true

    /**
     * 启动服务。
     *
     * 失败时抛异常（端口被占用、局域网模式下未启用鉴权等），由调用方决定如何提示用户。
     */
    @Synchronized
    fun start() {
        if (isRunning) {
            return
        }
        val violation = McpSecurity.lanModeViolation(McpConfig.allowLan, McpConfig.tokenRequired)
        check(violation == null) { violation!! }

        // 提前生成 Token，保证用户能在设置里复制到有效值
        if (McpConfig.tokenRequired) {
            McpConfig.token()
        }

        val port = McpConfig.port
        val host = McpConfig.host
        val engine = WebSocketServer()
        engine.listenHttp(port, host) {
            post(McpConfig.PATH) { handlePost(call) }
            get(McpConfig.PATH) { handleWrongMethod(call) }
            // 旧版 HTTP+SSE（2025-03-26）本服务不支持，给出明确诊断而不是静默 404
            get("/sse") { handleLegacySse(call) }
        }
        server = engine
        _running.value = true
        Pref.setMcpEnabled(true)
        McpForegroundService.start(GlobalAppContext.get())
        Log.i(TAG, "started on $host:$port${McpConfig.PATH}, tools=${registry.size}")
    }

    @Synchronized
    fun stop() {
        server?.stop()
        server = null
        _running.value = false
        Pref.setMcpEnabled(false)
        McpForegroundService.stop(GlobalAppContext.get())
        Log.i(TAG, "stopped")
    }

    /** 端口/绑定地址变化后重启引擎 */
    @Synchronized
    fun restart() {
        stop()
        start()
    }

    // ------------------------------------------------------------------ HTTP

    private suspend fun handlePost(call: ApplicationCall) {
        val startedAt = System.currentTimeMillis()

        if (!checkContentType(call)) return
        if (!checkAccept(call)) return
        if (!checkSecurity(call)) return
        if (!checkBodySize(call)) return

        val bodyText = call.receiveText()
        val element = JsonUtil.dispatchJson(bodyText)
        if (element == null) {
            respondJson(
                call, HttpStatusCode.BadRequest,
                McpJsonRpc.error(null, McpProtocol.ERROR_PARSE, "请求体不是合法 JSON")
            )
            return
        }
        if (!element.isJsonObject) {
            respondJson(
                call, HttpStatusCode.BadRequest,
                McpJsonRpc.error(null, McpProtocol.ERROR_INVALID_REQUEST, "请求体必须是 JSON-RPC 对象")
            )
            return
        }

        val root = element.asJsonObject

        // 通知：规范未定义通知 POST 的头部要求，一律接受并回 202
        if (McpJsonRpc.isNotification(root)) {
            respondAccepted(call)
            return
        }

        val params = McpJsonRpc.paramsOf(root)
        val headerVersion = call.request.header(McpProtocol.HEADER_PROTOCOL_VERSION)
        if (!checkProtocolVersion(call, root, params, headerVersion)) return

        val context = McpCallContext.from(params, headerVersion)
        val method = McpJsonRpc.methodOf(root)

        val result = dispatcher.dispatch(root, context)

        // 旧握手的会话头：常量回显，从不校验
        if (method == McpProtocol.METHOD_INITIALIZE) {
            call.response.header(McpProtocol.HEADER_SESSION_ID, McpProtocol.LEGACY_SESSION_ID)
        }

        when (result) {
            is McpDispatchResult.Reply -> respondJson(call, HttpStatusCode.OK, result.body)
            McpDispatchResult.Accepted -> respondAccepted(call)
            is McpDispatchResult.Failed -> respondJson(call, result.status, result.body)
        }

        audit(call, method, System.currentTimeMillis() - startedAt)
    }

    /** GET /mcp —— 2026-07-28 已移除 GET 流端点 */
    private suspend fun handleWrongMethod(call: ApplicationCall) {
        call.response.header(HttpHeaders.Allow, "POST")
        respondJson(
            call, HttpStatusCode.MethodNotAllowed,
            McpJsonRpc.error(
                null, McpProtocol.ERROR_INVALID_REQUEST,
                "MCP 端点只接受 POST（2026-07-28 起 GET 流端点已被移除）"
            )
        )
    }

    private suspend fun handleLegacySse(call: ApplicationCall) {
        respondJson(
            call, HttpStatusCode.NotFound,
            JsonObject().apply {
                addProperty("error", "本服务不支持 2025-03-26 的 HTTP+SSE 传输")
                addProperty("hint", "请改用 Streamable HTTP：POST ${McpConfig.PATH}")
                addProperty("protocolVersion", McpProtocol.VERSION)
            }
        )
    }

    // ------------------------------------------------------------- 前置校验

    private suspend fun checkContentType(call: ApplicationCall): Boolean {
        val raw = call.request.header(HttpHeaders.ContentType).orEmpty()
        val normalized = raw.substringBefore(';').trim().lowercase()
        if (normalized == "application/json") {
            return true
        }
        respondJson(
            call, HttpStatusCode.UnsupportedMediaType,
            McpJsonRpc.error(null, McpProtocol.ERROR_INVALID_REQUEST, "Content-Type 必须是 application/json")
        )
        return false
    }

    private suspend fun checkAccept(call: ApplicationCall): Boolean {
        val accept = call.request.header(HttpHeaders.Accept).orEmpty()
        if (accept.isBlank() ||
            accept.contains("application/json", ignoreCase = true) ||
            accept.contains("*/*", ignoreCase = true)
        ) {
            return true
        }
        respondJson(
            call, HttpStatusCode.NotAcceptable,
            McpJsonRpc.error(null, McpProtocol.ERROR_INVALID_REQUEST, "Accept 必须包含 application/json")
        )
        return false
    }

    private suspend fun checkSecurity(call: ApplicationCall): Boolean {
        val port = McpConfig.port
        val allowLan = McpConfig.allowLan

        McpSecurity.checkOrigin(call.request.header(HttpHeaders.Origin), port, allowLan)?.let { reason ->
            Log.w(TAG, "reject origin: $reason")
            respondJson(call, HttpStatusCode.Forbidden, McpJsonRpc.error(null, McpProtocol.ERROR_INVALID_REQUEST, reason))
            return false
        }
        McpSecurity.checkHost(call.request.header(HttpHeaders.Host), allowLan)?.let { reason ->
            Log.w(TAG, "reject host: $reason")
            respondJson(call, HttpStatusCode.Forbidden, McpJsonRpc.error(null, McpProtocol.ERROR_INVALID_REQUEST, reason))
            return false
        }

        val required = McpConfig.tokenRequired
        val expected = if (required) McpConfig.token() else ""
        McpSecurity.checkToken(call.request.header(HttpHeaders.Authorization), required, expected)?.let { reason ->
            Log.w(TAG, "reject token: $reason")
            call.response.header(HttpHeaders.WWWAuthenticate, "Bearer realm=\"AutoX MCP\"")
            respondJson(call, HttpStatusCode.Unauthorized, McpJsonRpc.error(null, McpProtocol.ERROR_INVALID_REQUEST, reason))
            return false
        }
        return true
    }

    private suspend fun checkBodySize(call: ApplicationCall): Boolean {
        val declared = call.request.header(HttpHeaders.ContentLength)?.toLongOrNull()
        if (declared != null && declared > McpConfig.MAX_REQUEST_BYTES) {
            respondJson(
                call, HttpStatusCode.PayloadTooLarge,
                McpJsonRpc.error(null, McpProtocol.ERROR_INVALID_REQUEST, "请求体超过上限 ${McpConfig.MAX_REQUEST_BYTES} 字节")
            )
            return false
        }
        return true
    }

    /**
     * 协议版本校验。
     *
     * - 未带版本头：规范允许服务端视为 2025-03-26，走旧握手分支，放行。
     * - 带了版本头但与 `_meta` 不一致：400 + HeaderMismatchError(-32020)。
     * - 版本不受支持：400 + UnsupportedProtocolVersionError(-32022)。
     */
    private suspend fun checkProtocolVersion(
        call: ApplicationCall,
        root: JsonObject,
        params: JsonObject?,
        headerVersion: String?,
    ): Boolean {
        if (headerVersion == null) {
            return true
        }
        val id = McpJsonRpc.idOf(root)
        val metaVersion = params?.get("_meta")
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?.get(McpProtocol.META_PROTOCOL_VERSION)
            ?.takeIf { it.isJsonPrimitive }?.asString

        if (metaVersion != null && metaVersion != headerVersion) {
            respondJson(
                call, HttpStatusCode.BadRequest,
                McpJsonRpc.error(
                    id, McpProtocol.ERROR_HEADER_MISMATCH,
                    "Header mismatch: MCP-Protocol-Version '$headerVersion' 与 _meta 中的 '$metaVersion' 不一致"
                )
            )
            return false
        }
        if (!McpProtocol.isSupported(headerVersion)) {
            respondJson(
                call, HttpStatusCode.BadRequest,
                McpJsonRpc.error(
                    id, McpProtocol.ERROR_UNSUPPORTED_PROTOCOL_VERSION,
                    "不支持的协议版本",
                    McpJsonRpc.unsupportedVersionData(headerVersion)
                )
            )
            return false
        }
        return true
    }

    // ----------------------------------------------------------------- 响应

    private suspend fun respondJson(call: ApplicationCall, status: HttpStatusCode, body: JsonObject) {
        call.response.header(HttpHeaders.CacheControl, "no-store")
        call.respondText(body.toString(), ContentType.Application.Json, status)
    }

    private suspend fun respondAccepted(call: ApplicationCall) {
        call.respondText("", ContentType.Text.Plain, HttpStatusCode.Accepted)
    }

    private fun audit(call: ApplicationCall, method: String?, elapsedMs: Long) {
        if (!McpConfig.auditLogEnabled) {
            return
        }
        val remote = call.request.header("X-Forwarded-For")
            ?: call.request.local.remoteHost
        Log.i(TAG, "call remote=$remote method=$method elapsed=${elapsedMs}ms")
    }
}
