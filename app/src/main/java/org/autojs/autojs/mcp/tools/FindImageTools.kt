package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.autojs.core.image.TemplateMatching
import com.stardust.autojs.core.opencv.Mat
import com.stardust.autojs.core.opencv.OpenCVHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.autojs.autojs.mcp.McpCallContext
import org.autojs.autojs.mcp.McpConfig
import org.opencv.imgproc.Imgproc

private fun templateSchema(): Map<String, JsonObject> = mapOf(
    "template" to stringProp("模板图 PNG/JPEG 的 base64"),
    "image" to stringProp("待搜图 base64，缺省现截一屏"),
    "threshold" to JsonObject().apply {
        addProperty("type", "number")
        addProperty("description", "判定阈值（0~1），越高越严")
        addProperty("default", 0.9)
        addProperty("minimum", 0)
        addProperty("maximum", 1)
    },
    "weakThreshold" to JsonObject().apply {
        addProperty("type", "number")
        addProperty("description", "金字塔粗筛阈值（0~1），越低越慢越全")
        addProperty("default", 0.7)
        addProperty("minimum", 0)
        addProperty("maximum", 1)
    },
)

/** find_image：找首个匹配（金字塔模板匹配），找不到回 found:false 而非报错. */
internal class FindImageTool : McpTool {
    override val name = "find_image"
    override val description = "在屏幕（或给定图）中找模板图，返回中心点物理像素坐标与相似度。找不到返回 found:false。"
    override val inputSchema: JsonObject = schemaOf(
        templateSchema() + mapOf("region" to McpImageOps.regionSchema()),
        required = listOf("template"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val strict = arguments.optDouble("threshold", 0.9).coerceIn(0.0, 1.0).toFloat()
        val weak = arguments.optDouble("weakThreshold", 0.7).coerceIn(0.0, 1.0).toFloat()
        val template = McpImageOps.decodeImage(arguments.reqString("template"), "template")
        val source = McpImageOps.sourceImage(arguments)
        try {
            McpImageOps.ensureOpenCv()
            val matches = match(source, template, weak, strict, arguments, 1)
            if (matches.isEmpty()) {
                return McpToolResult.json(JsonObject().apply { addProperty("found", false) })
            }
            val m = matches[0]
            return McpToolResult.json(JsonObject().apply {
                addProperty("found", true)
                addProperty("x", m.first)
                addProperty("y", m.second)
                addProperty("similarity", m.third)
            })
        } finally {
            runCatching { template.recycle() }
            runCatching { source.recycle() }
        }
    }
}

/** find_all_images：找全部匹配并按相似度排序截断. */
internal class FindAllImagesTool : McpTool {
    override val name = "find_all_images"
    override val description = "在屏幕（或给定图）中找模板图全部匹配，返回坐标数组（物理像素）。无匹配返回空数组。"
    override val inputSchema: JsonObject = schemaOf(
        templateSchema() + mapOf(
            "region" to McpImageOps.regionSchema(),
            "limit" to intProp("最多返回个数", 5, 1, 20),
        ),
        required = listOf("template"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val strict = arguments.optDouble("threshold", 0.9).coerceIn(0.0, 1.0).toFloat()
        val weak = arguments.optDouble("weakThreshold", 0.7).coerceIn(0.0, 1.0).toFloat()
        val limit = arguments.optInt("limit", 5).coerceIn(1, 20)
        val template = McpImageOps.decodeImage(arguments.reqString("template"), "template")
        val source = McpImageOps.sourceImage(arguments)
        try {
            McpImageOps.ensureOpenCv()
            val matches = match(source, template, weak, strict, arguments, limit)
            return McpToolResult.json(JsonObject().apply {
                addProperty("count", matches.size)
                add("matches", JsonArray().apply {
                    matches.forEach { (x, y, s) ->
                        add(JsonObject().apply {
                            addProperty("x", x)
                            addProperty("y", y)
                            addProperty("similarity", s)
                        })
                    }
                })
            })
        } finally {
            runCatching { template.recycle() }
            runCatching { source.recycle() }
        }
    }
}

private suspend fun match(
    source: com.stardust.autojs.core.image.ImageWrapper,
    template: com.stardust.autojs.core.image.ImageWrapper,
    weak: Float,
    strict: Float,
    arguments: JsonObject,
    limit: Int,
): List<Triple<Int, Int, Double>> {
    if (template.width > source.width || template.height > source.height) {
        throw McpToolException(McpErrorCode.INVALID_IMAGE, "模板图比待搜图大", "请确认 template 与屏幕对应")
    }
    try {
        return withTimeout(McpConfig.MATCH_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                val region = McpImageOps.parseRegion(arguments, source.width, source.height)
                var searched = source.mat
                var view: Mat? = null
                if (region != null) {
                    view = Mat(searched, region)
                    searched = view
                }
                try {
                    TemplateMatching.fastTemplateMatching(
                        searched, template.mat, Imgproc.TM_CCOEFF_NORMED,
                        weak, strict, TemplateMatching.MAX_LEVEL_AUTO, limit,
                    ).map { m ->
                        val x = (m.point.x + (region?.x?.toDouble() ?: 0.0)).toInt()
                        val y = (m.point.y + (region?.y?.toDouble() ?: 0.0)).toInt()
                        Triple(x, y, m.similarity)
                    }
                } finally {
                    if (view != null) OpenCVHelper.release(view)
                }
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw McpToolException(McpErrorCode.TIMEOUT, "找图超时", "缩小 region 或降低 weakThreshold 精度要求后重试")
    }
}
