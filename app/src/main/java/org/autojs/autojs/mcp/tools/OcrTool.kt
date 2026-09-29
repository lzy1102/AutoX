package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.autojs.core.mlkit.GoogleMLKitOcrResult
import com.stardust.autojs.runtime.api.GoogleMLKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.autojs.autojs.mcp.McpCallContext
import org.autojs.autojs.mcp.McpConfig

/** ocr_recognize：截屏并用 MLKit 识别文字. */
internal class OcrTool : McpTool {
    override val name = "ocr_recognize"
    override val description = """
        截取当前屏幕并识别文字，返回全文与按行拆分的位置（bounds 为物理像素）。
        language：zh（中文）/ ja / ko / sa / latin，默认 zh。
    """.trimIndent()
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "language" to stringProp("识别语言：zh/ja/ko/sa/latin", "zh"),
            "maxWidth" to intProp("识别前截图的最大宽度", McpConfig.DEFAULT_CAPTURE_MAX_WIDTH, 0, 4096),
        ),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val language = arguments.optString("language", "zh")?.trim()?.ifEmpty { "zh" } ?: "zh"
        val maxWidth = arguments.optInt("maxWidth", McpConfig.DEFAULT_CAPTURE_MAX_WIDTH)
        val wrapper = McpServices.captureImage()
        try {
            val result = try {
                withTimeout(McpConfig.OCR_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) { GoogleMLKit().ocr(wrapper, language) }
                }
            } catch (e: TimeoutCancellationException) {
                throw McpToolException(McpErrorCode.TIMEOUT, "OCR 超时", "请重试")
            }
            if (result == null) {
                throw McpToolException(McpErrorCode.OCR_NOT_READY, "OCR 无结果（模型可能未就绪）", "请重试")
            }
            val lines = flattenLines(result)
            val structured = JsonObject().apply {
                addProperty("fullText", result.text)
                add("lines", JsonArray().apply {
                    lines.forEach { line ->
                        add(JsonObject().apply {
                            addProperty("text", line.text)
                            line.bounds?.let { b ->
                                add("bounds", JsonObject().apply {
                                    addProperty("left", b.left)
                                    addProperty("top", b.top)
                                    addProperty("right", b.right)
                                    addProperty("bottom", b.bottom)
                                })
                            }
                            addProperty("confidence", line.confidence)
                        })
                    }
                })
                addProperty("imageWidth", wrapper.width)
                addProperty("imageHeight", wrapper.height)
            }
            // 同时回一张缩放图，便于模型对照位置.
            val encoded = runCatching { McpImages.encode(wrapper, maxWidth) }.getOrNull()
            return if (encoded != null) {
                McpToolResult.textAndImage(structured.toString(), encoded.base64, encoded.mimeType)
            } else {
                McpToolResult.json(structured)
            }
        } finally {
            runCatching { wrapper.recycle() }
        }
    }

    private fun flattenLines(root: GoogleMLKitOcrResult): List<GoogleMLKitOcrResult> {
        // level: 0 全文 1 块 2 行 3 字 —— 取行级，兼容块直挂文本的情况.
        val lines = runCatching { root.toArray(2) }.getOrDefault(emptyList())
        if (lines.isNotEmpty()) return lines
        return runCatching { root.toArray(1) }.getOrDefault(emptyList())
    }
}
