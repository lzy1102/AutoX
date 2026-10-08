package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.autojs.core.image.ColorFinder
import com.stardust.util.ScreenMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.autojs.autojs.mcp.McpCallContext
import org.autojs.autojs.mcp.McpConfig
import org.opencv.core.Point

private fun colorBaseSchema(): Map<String, JsonObject> = mapOf(
    "image" to stringProp("待搜图 base64，缺省现截一屏"),
    "region" to McpImageOps.regionSchema(),
    "dir" to intProp("查找方向：0=左上→右下 1=中心向外 2=右下→左上 3=左下→右上 4=右上→左下", 0, 0, 4),
)

private suspend fun <T> withMatchTimeout(block: suspend () -> T): T {
    try {
        return withTimeout(McpConfig.MATCH_TIMEOUT_MS) { block() }
    } catch (e: TimeoutCancellationException) {
        throw McpToolException(McpErrorCode.TIMEOUT, "找色超时", "缩小 region 后重试")
    }
}

/** find_color：单色查找，返回首个匹配点（物理像素）. */
internal class FindColorTool : McpTool {
    override val name = "find_color"
    override val description = "在屏幕（或给定图）中找颜色，返回首个匹配点物理像素坐标。找不到返回 found:false。"
    override val inputSchema: JsonObject = schemaOf(
        colorBaseSchema() + mapOf(
            "color" to stringProp("颜色，如 \"#FF0000\""),
            "tolerance" to intProp("容差 0~255", 4, 0, 255),
        ),
        required = listOf("color"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val color = McpImageOps.parseColor(arguments.reqString("color"))
        val tol = McpImageOps.checkTolerance(arguments.optInt("tolerance", 4))
        val dir = McpImageOps.checkDir(arguments.optInt("dir", 0))
        val source = McpImageOps.sourceImage(arguments)
        try {
            McpImageOps.ensureOpenCv()
            val region = McpImageOps.parseRegion(arguments, source.width, source.height)
            val finder = ColorFinder(ScreenMetrics())
            val point = withMatchTimeout {
                withContext(Dispatchers.IO) {
                    finder.findColor(source, intArrayOf(color), intArrayOf(tol), region, dir)
                }
            }
            return pointResult(point)
        } finally {
            runCatching { source.recycle() }
        }
    }
}

/** find_all_colors：多候选色查找全部匹配点. */
internal class FindAllColorsTool : McpTool {
    override val name = "find_all_colors"
    override val description = "在屏幕（或给定图）中找多个候选色全部匹配点，返回坐标数组。无匹配返回空数组。"
    override val inputSchema: JsonObject = schemaOf(
        colorBaseSchema() + mapOf(
            "colors" to JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "候选颜色数组，如 [\"#FF0000\",\"#00FF00\"]，最多10个")
            },
            "tolerance" to intProp("容差 0~255（作用于全部候选色）", 4, 0, 255),
            "limit" to intProp("最多返回个数", 100, 1, 1000),
        ),
        required = listOf("colors"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val arr = arguments.get("colors")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "colors 须为数组", "如 [\"#FF0000\"]")
        val colors = McpImageOps.parseColors(arr)
        val tol = McpImageOps.checkTolerance(arguments.optInt("tolerance", 4))
        val dir = McpImageOps.checkDir(arguments.optInt("dir", 0))
        val limit = arguments.optInt("limit", 100).coerceIn(1, 1000)
        val source = McpImageOps.sourceImage(arguments)
        try {
            McpImageOps.ensureOpenCv()
            val region = McpImageOps.parseRegion(arguments, source.width, source.height)
            val finder = ColorFinder(ScreenMetrics())
            val points = withMatchTimeout {
                withContext(Dispatchers.IO) {
                    finder.findAllColors(source, colors, IntArray(colors.size) { tol }, region, dir, limit)
                }
            }
            return McpToolResult.json(JsonObject().apply {
                addProperty("count", points.size)
                add("points", JsonArray().apply {
                    points.forEach { add(McpImageOps.pointJson(it.x.toInt(), it.y.toInt())) }
                })
            })
        } finally {
            runCatching { source.recycle() }
        }
    }
}

/** find_multi_colors：多点找色（首色 + 相对偏移点组）. */
internal class FindMultiColorsTool : McpTool {
    override val name = "find_multi_colors"
    override val description = "多点找色：先找首色，再校验相对偏移点颜色。points 为 [{dx,dy,color,tolerance?}]。返回首色坐标。"
    override val inputSchema: JsonObject = schemaOf(
        colorBaseSchema() + mapOf(
            "firstColor" to stringProp("首色，如 \"#FF0000\""),
            "firstTolerance" to intProp("首色容差", 4, 0, 255),
            "points" to JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "偏移点 [{dx,dy,color,tolerance?}]，最多20个")
            },
        ),
        required = listOf("firstColor", "points"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val firstColor = McpImageOps.parseColor(arguments.reqString("firstColor"))
        val firstTol = McpImageOps.checkTolerance(arguments.optInt("firstTolerance", 4))
        val dir = McpImageOps.checkDir(arguments.optInt("dir", 0))
        val arr = arguments.get("points")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "points 须为数组", null)
        if (arr.size() == 0 || arr.size() > 20) {
            throw McpToolException(McpErrorCode.INVALID_PARAMS, "points 须 1~20 个", null)
        }
        val flat = ArrayList<Int>()
        arr.forEachIndexed { i, e ->
            val o = e.takeIf { it.isJsonObject }?.asJsonObject
                ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "points[$i] 须为对象", "如 {\"dx\":10,\"dy\":5,\"color\":\"#00FF00\"}")
            val dx = o.optInt("dx", Int.MIN_VALUE)
            val dy = o.optInt("dy", Int.MIN_VALUE)
            if (dx == Int.MIN_VALUE || dy == Int.MIN_VALUE) {
                throw McpToolException(McpErrorCode.INVALID_PARAMS, "points[$i] 缺少 dx/dy", null)
            }
            val c = McpImageOps.parseColor(o.optString("color", null)
                ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "points[$i] 缺少 color", null))
            val t = McpImageOps.checkTolerance(o.optInt("tolerance", firstTol))
            flat.add(dx); flat.add(dy); flat.add(1); flat.add(c); flat.add(t)
        }
        val source = McpImageOps.sourceImage(arguments)
        try {
            McpImageOps.ensureOpenCv()
            val region = McpImageOps.parseRegion(arguments, source.width, source.height)
            val finder = ColorFinder(ScreenMetrics())
            val points = withMatchTimeout {
                withContext(Dispatchers.IO) {
                    finder.findMultiColors(
                        source, intArrayOf(firstColor), intArrayOf(firstTol),
                        region, flat.toIntArray(), dir, false,
                    )
                }
            }
            return pointResult(points.firstOrNull())
        } finally {
            runCatching { source.recycle() }
        }
    }
}

/** cmp_colors：校验一组坐标颜色是否匹配. */
internal class CmpColorsTool : McpTool {
    override val name = "cmp_colors"
    override val description = "校验屏幕（或给定图）上一组坐标的颜色，全部匹配返回 match:true。points 为 [{x,y,color,tolerance?}]。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "image" to stringProp("待验图 base64，缺省现截一屏"),
            "points" to JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "校验点 [{x,y,color,tolerance?}]，最多20个")
            },
        ),
        required = listOf("points"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val arr = arguments.get("points")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "points 须为数组", null)
        if (arr.size() == 0 || arr.size() > 20) {
            throw McpToolException(McpErrorCode.INVALID_PARAMS, "points 须 1~20 个", null)
        }
        val source = McpImageOps.sourceImage(arguments)
        try {
            McpImageOps.ensureOpenCv()
            val flat = ArrayList<Int>()
            arr.forEachIndexed { i, e ->
                val o = e.takeIf { it.isJsonObject }?.asJsonObject
                    ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "points[$i] 须为对象", null)
                val x = o.optInt("x", -1)
                val y = o.optInt("y", -1)
                if (x < 0 || y < 0 || x >= source.width || y >= source.height) {
                    throw McpToolException(McpErrorCode.INVALID_PARAMS, "points[$i] 坐标超出图片（${source.width}x${source.height}）", "坐标为物理像素")
                }
                val c = McpImageOps.parseColor(o.optString("color", null)
                    ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "points[$i] 缺少 color", null))
                val t = McpImageOps.checkTolerance(o.optInt("tolerance", 4))
                flat.add(x); flat.add(y); flat.add(1); flat.add(c); flat.add(t)
            }
            val finder = ColorFinder(ScreenMetrics())
            val ok = withMatchTimeout {
                withContext(Dispatchers.IO) { finder.cmpColorEx(source, flat.toIntArray()) }
            }
            return McpToolResult.json(JsonObject().apply { addProperty("match", ok) })
        } finally {
            runCatching { source.recycle() }
        }
    }
}

private fun pointResult(point: Point?): McpToolResult {
    if (point == null) {
        return McpToolResult.json(JsonObject().apply { addProperty("found", false) })
    }
    return McpToolResult.json(JsonObject().apply {
        addProperty("found", true)
        addProperty("x", point.x.toInt())
        addProperty("y", point.y.toInt())
    })
}
