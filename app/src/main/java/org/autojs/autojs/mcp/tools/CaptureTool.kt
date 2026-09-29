package org.autojs.autojs.mcp.tools

import com.google.gson.JsonObject
import org.autojs.autojs.mcp.McpCallContext
import org.autojs.autojs.mcp.McpConfig

/** screen_capture：截当前屏并以 JPEG base64 返回. */
internal class CaptureTool : McpTool {
    override val name = "screen_capture"
    override val description = """
        截取当前屏幕，返回 JPEG 图片。坐标系为设备物理像素，返回的 width/height 即本次截图尺寸。
        首次调用需在手机上手动确认录屏授权；input_* 的坐标可参考本次返回尺寸。
    """.trimIndent()
    override val inputSchema: JsonObject = schemaOf(
        mapOf("maxWidth" to intProp("返回图片的最大宽度，0 表示不缩放", McpConfig.DEFAULT_CAPTURE_MAX_WIDTH, 0, 4096)),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val maxWidth = arguments.optInt("maxWidth", McpConfig.DEFAULT_CAPTURE_MAX_WIDTH)
        val wrapper = McpServices.captureImage()
        try {
            val encoded = McpImages.encode(wrapper, maxWidth)
            val text = "截图 ${encoded.width}x${encoded.height}（物理像素）"
            return McpToolResult.textAndImage(text, encoded.base64, encoded.mimeType)
        } finally {
            runCatching { wrapper.recycle() }
        }
    }
}
