package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.autojs.autojs.mcp.McpProtocol

/**
 * `tools/call` 的 result。
 *
 * 按 2026-07-28 规范，result 必带 `resultType`；`isError` 用于表达「工具业务失败」，
 * 这类失败不影响 JSON-RPC 信封本身，所以仍是一个成功响应。
 */
class McpToolResult private constructor(
    private val content: List<JsonObject>,
    private val structuredContent: JsonElement? = null,
    private val isError: Boolean = false,
) {

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty(McpProtocol.RESULT_TYPE, McpProtocol.RESULT_TYPE_COMPLETE)
        add("content", JsonArray().apply { content.forEach { add(it) } })
        addProperty("isError", isError)
        structuredContent?.let { add("structuredContent", it) }
    }

    companion object {

        /** 纯文本结果 */
        fun text(text: String): McpToolResult = McpToolResult(listOf(textContent(text)))

        /**
         * 结构化结果。
         *
         * 规范建议返回 structuredContent 的工具同时给一份序列化 JSON 的文本块，
         * 这里在 [text] 为 null 时自动补上序列化结果。
         */
        fun json(structured: JsonElement, text: String? = null): McpToolResult =
            McpToolResult(
                content = listOf(textContent(text ?: structured.toString())),
                structuredContent = structured,
            )

        /** 图片结果。[description] 作为同行的文本块，便于模型理解这张图是什么 */
        fun image(base64: String, mimeType: String, description: String? = null): McpToolResult {
            val items = ArrayList<JsonObject>()
            description?.let { items.add(textContent(it)) }
            items.add(
                JsonObject().apply {
                    addProperty("type", "image")
                    addProperty("data", base64)
                    addProperty("mimeType", mimeType)
                }
            )
            return McpToolResult(items)
        }

        /** 文本 + 图片 */
        fun textAndImage(text: String, base64: String, mimeType: String): McpToolResult =
            McpToolResult(
                listOf(
                    textContent(text),
                    JsonObject().apply {
                        addProperty("type", "image")
                        addProperty("data", base64)
                        addProperty("mimeType", mimeType)
                    },
                )
            )

        /** 业务失败的带内错误。模型可读 [hint] 自我纠正 */
        fun error(code: String, message: String, hint: String? = null): McpToolResult =
            McpToolResult(
                content = listOf(textContent(if (hint.isNullOrBlank()) message else "$message（$hint）")),
                structuredContent = JsonObject().apply {
                    addProperty("errorCode", code)
                    hint?.let { addProperty("hint", it) }
                },
                isError = true,
            )

        fun textContent(text: String): JsonObject = JsonObject().apply {
            addProperty("type", "text")
            addProperty("text", text)
        }
    }
}
