package org.autojs.autojs.mcp

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject

/**
 * JSON-RPC 2.0 信封的构造与解析。
 *
 * MCP 要求 `id` 保真回显（可能是数字、字符串或 null），因此这里一律用 [JsonElement] 透传，
 * 不做类型收敛。
 */
object McpJsonRpc {

    const val FIELD_JSONRPC = "jsonrpc"
    const val FIELD_ID = "id"
    const val FIELD_METHOD = "method"
    const val FIELD_PARAMS = "params"
    const val FIELD_RESULT = "result"
    const val FIELD_ERROR = "error"

    fun success(id: JsonElement?, result: JsonObject): JsonObject = JsonObject().apply {
        addProperty(FIELD_JSONRPC, "2.0")
        add(FIELD_ID, id ?: JsonNull.INSTANCE)
        add(FIELD_RESULT, result)
    }

    fun error(id: JsonElement?, code: Int, message: String, data: JsonElement? = null): JsonObject =
        JsonObject().apply {
            addProperty(FIELD_JSONRPC, "2.0")
            add(FIELD_ID, id ?: JsonNull.INSTANCE)
            add(FIELD_ERROR, JsonObject().apply {
                addProperty("code", code)
                addProperty("message", message)
                data?.let { add("data", it) }
            })
        }

    /** 版本不支持错误的 data，形如 `{"supported":[...],"requested":"..."}` */
    fun unsupportedVersionData(requested: String?): JsonObject = JsonObject().apply {
        add("supported", JsonArray().apply { McpProtocol.SUPPORTED_VERSIONS.forEach { add(it) } })
        if (requested == null) {
            add(FIELD_ID, JsonNull.INSTANCE)
        } else {
            addProperty("requested", requested)
        }
    }

    /**
     * 是否为通知（无 `id` 成员）。
     *
     * 注意 `"id": null` 仍算请求，需要回包；只有成员缺失才是通知。
     */
    fun isNotification(root: JsonObject): Boolean = !root.has(FIELD_ID)

    fun idOf(root: JsonObject): JsonElement? = root.get(FIELD_ID)

    fun methodOf(root: JsonObject): String? {
        val element = root.get(FIELD_METHOD) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) {
            return null
        }
        return element.asString
    }

    fun paramsOf(root: JsonObject): JsonObject? =
        root.get(FIELD_PARAMS)?.takeIf { it.isJsonObject }?.asJsonObject
}
