package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.autojs.autojs.mcp.tools.McpTool

/**
 * 工具注册表。
 *
 * 规范要求 `tools/list` 的结果不能随连接变化，且顺序需确定（便于客户端缓存与命中原生 prompt 缓存），
 * 因此用 [LinkedHashMap] 保持注册顺序。
 *
 * 工具数远小于分页阈值，故不实现 `cursor` 分页，响应中不出现 `nextCursor`。
 */
class McpRegistry {

    private val tools = LinkedHashMap<String, McpTool>()

    fun register(tool: McpTool) {
        tools[tool.name] = tool
    }

    fun registerAll(vararg list: McpTool) {
        list.forEach { register(it) }
    }

    fun get(name: String): McpTool? = tools[name]

    fun all(): List<McpTool> = tools.values.toList()

    val size: Int get() = tools.size

    fun names(): List<String> = tools.keys.toList()

    /** `tools/list` 的 result */
    fun toolsListResult(): JsonObject = JsonObject().apply {
        addProperty(McpProtocol.RESULT_TYPE, McpProtocol.RESULT_TYPE_COMPLETE)
        add("tools", JsonArray().apply { all().forEach { add(it.toJson()) } })
        addProperty(McpProtocol.TTL_MS, McpConfig.TOOLS_TTL_MS)
        addProperty(McpProtocol.CACHE_SCOPE, McpProtocol.CACHE_SCOPE_PUBLIC)
    }
}
