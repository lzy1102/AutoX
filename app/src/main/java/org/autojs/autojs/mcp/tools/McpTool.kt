package org.autojs.autojs.mcp.tools

import com.google.gson.JsonObject
import org.autojs.autojs.mcp.McpCallContext

/**
 * 一个可被模型调用的 MCP 工具。
 *
 * 实现类负责：声明入参 schema、校验入参、执行业务、把结果转成 [McpToolResult]。
 * 业务失败请抛 [McpToolException]（会转成 `isError:true` 的带内错误），不要抛裸异常——
 * 裸异常会被兜底成 INTERNAL，模型无法据此自纠。
 */
interface McpTool {

    /** 工具名。规范允许 A-Za-z0-9_-.，长度 1-128，本服务统一用小写 snake_case + 域前缀 */
    val name: String

    /** 供界面展示的可读名称 */
    val title: String? get() = null

    /** 给模型看的描述。前置条件（如需要无障碍/录屏/root）必须写清楚 */
    val description: String

    /** 入参 JSON Schema，必须是非 null 的合法 JSON Schema 对象 */
    val inputSchema: JsonObject

    /** 可选的工具行为注解 */
    val annotations: JsonObject? get() = null

    suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult

    /** 转为 tools/list 中的一个工具定义 */
    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("name", name)
        title?.let { addProperty("title", it) }
        addProperty("description", description)
        add("inputSchema", inputSchema)
        annotations?.let { add("annotations", it) }
    }
}

/**
 * 工具的业务失败。
 *
 * [code] 取 [McpErrorCode] 中的常量，[hint] 给模型一句可执行的下一步建议。
 */
class McpToolException(
    val code: String,
    message: String,
    val hint: String? = null,
) : Exception(message)

/**
 * 工具业务错误码。与协议层错误区分：这些走 `result.isError`，不是 JSON-RPC `error`。
 */
object McpErrorCode {
    const val NO_ACCESSIBILITY_SERVICE = "NO_ACCESSIBILITY_SERVICE"
    const val SCREEN_CAPTURE_NOT_GRANTED = "SCREEN_CAPTURE_NOT_GRANTED"
    const val NO_ROOT = "NO_ROOT"
    const val NO_FOREGROUND_ACTIVITY = "NO_FOREGROUND_ACTIVITY"
    const val NODE_NOT_FOUND = "NODE_NOT_FOUND"
    const val STALE_NODE = "STALE_NODE"
    const val GESTURE_FAILED = "GESTURE_FAILED"
    const val TIMEOUT = "TIMEOUT"
    const val INVALID_IMAGE = "INVALID_IMAGE"
    const val OCR_NOT_READY = "OCR_NOT_READY"
    const val UNSUPPORTED_API = "UNSUPPORTED_API"
    const val INVALID_PARAMS = "INVALID_PARAMS"
    const val INTERNAL = "INTERNAL"
}
