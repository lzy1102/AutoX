package org.autojs.autojs.mcp.tools

import com.google.gson.JsonObject
import com.stardust.util.ClipboardUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.autojs.autojs.mcp.McpCallContext

/** clipboard_get：读取系统剪贴板. */
internal class ClipboardGetTool : McpTool {
    override val name = "clipboard_get"
    override val description = """
        读取系统剪贴板文本。注意 Android 10+ 限制：仅前台应用可读剪贴板，
        返回空字符串时请让 AutoX 处于前台（或先 input_key 回桌面再调用）。
    """.trimIndent()
    override val inputSchema: JsonObject = schemaOf(emptyMap())

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.Main) {
            val text = ClipboardUtil.getClipOrEmpty(McpServices.context).toString()
            McpToolResult.json(JsonObject().apply {
                addProperty("text", text)
                addProperty("empty", text.isEmpty())
            })
        }
}

/** clipboard_set：写入系统剪贴板. */
internal class ClipboardSetTool : McpTool {
    override val name = "clipboard_set"
    override val description = "写入系统剪贴板文本，可配合 ui_input 的粘贴场景使用。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf("text" to stringProp("要写入的文本")),
        required = listOf("text"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.Main) {
            val text = arguments.optString("text", "") ?: ""
            ClipboardUtil.setClip(McpServices.context, text)
            McpToolResult.json(JsonObject().apply {
                addProperty("set", true)
                addProperty("length", text.length)
            })
        }
}
