package org.autojs.autojs.mcp.tools

import android.os.Build
import android.view.ViewConfiguration
import com.google.gson.JsonObject
import com.stardust.autojs.core.util.ProcessShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.autojs.autojs.mcp.McpCallContext
import org.autojs.autojs.mcp.McpConfig

private fun checkGestureTime(durationMs: Long) {
    if (durationMs <= 0 || durationMs > McpConfig.MAX_GESTURE_MS) {
        throw McpToolException(
            McpErrorCode.INVALID_PARAMS,
            "手势时长须在 1~${McpConfig.MAX_GESTURE_MS}ms 之间",
            "长手势请拆成多段调用",
        )
    }
}

private fun checkPoint(x: Int, y: Int) {
    if (x < 0 || y < 0 || x > 10000 || y > 10000) {
        throw McpToolException(McpErrorCode.INVALID_PARAMS, "坐标超出范围：($x,$y)", "坐标以物理像素为单位")
    }
}

/** input_tap：点击物理像素坐标. */
internal class InputTapTool : McpTool {
    override val name = "input_tap"
    override val description = "点击屏幕坐标（物理像素）。需要无障碍服务已开启，API24+。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "x" to intProp("横坐标（物理像素）", null, 0, 10000),
            "y" to intProp("纵坐标（物理像素）", null, 0, 10000),
        ),
        required = listOf("x", "y"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                throw McpToolException(McpErrorCode.UNSUPPORTED_API, "手势需要 Android 7.0+", null)
            }
            val x = arguments.optInt("x", -1)
            val y = arguments.optInt("y", -1)
            checkPoint(x, y)
            val ok = McpServices.automator().click(x, y)
            if (!ok) throw McpToolException(McpErrorCode.GESTURE_FAILED, "点击未被系统接受", "确认坐标在屏幕内且无障碍已开启")
            McpToolResult.json(JsonObject().apply { addProperty("tapped", true) })
        }
}

/** input_swipe：滑动. */
internal class InputSwipeTool : McpTool {
    override val name = "input_swipe"
    override val description = "从 (x1,y1) 滑到 (x2,y2)，durationMs 为时长。坐标物理像素。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "x1" to intProp("起点横坐标", null, 0, 10000),
            "y1" to intProp("起点纵坐标", null, 0, 10000),
            "x2" to intProp("终点横坐标", null, 0, 10000),
            "y2" to intProp("终点纵坐标", null, 0, 10000),
            "durationMs" to intProp("滑动时长毫秒", 500, 1, 10000),
        ),
        required = listOf("x1", "y1", "x2", "y2"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                throw McpToolException(McpErrorCode.UNSUPPORTED_API, "手势需要 Android 7.0+", null)
            }
            val x1 = arguments.optInt("x1", -1)
            val y1 = arguments.optInt("y1", -1)
            val x2 = arguments.optInt("x2", -1)
            val y2 = arguments.optInt("y2", -1)
            checkPoint(x1, y1); checkPoint(x2, y2)
            val duration = arguments.optLong("durationMs", 500L)
            checkGestureTime(duration)
            val ok = McpServices.automator().swipe(x1, y1, x2, y2, duration)
            if (!ok) throw McpToolException(McpErrorCode.GESTURE_FAILED, "滑动未被系统接受", "确认无障碍已开启后重试")
            McpToolResult.json(JsonObject().apply { addProperty("swiped", true) })
        }
}

/** input_gesture：多点折线手势. */
internal class InputGestureTool : McpTool {
    override val name = "input_gesture"
    override val description = "折线手势：points 为 [x,y] 序列，durationMs 为总时长。长按可用两点相同坐标+时长实现。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "durationMs" to intProp("手势总时长毫秒", null, 1, 10000),
            "points" to JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "轨迹点 [[x,y],...]，至少1个点")
            },
        ),
        required = listOf("durationMs", "points"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                throw McpToolException(McpErrorCode.UNSUPPORTED_API, "手势需要 Android 7.0+", null)
            }
            val duration = arguments.optLong("durationMs", -1L)
            checkGestureTime(duration)
            val arr = arguments.get("points")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "points 须为 [[x,y],...]", null)
            if (arr.size() == 0) {
                throw McpToolException(McpErrorCode.INVALID_PARAMS, "points 至少1个点", null)
            }
            if (arr.size() > 20) {
                throw McpToolException(McpErrorCode.INVALID_PARAMS, "points 最多20个点", "请简化轨迹后重试")
            }
            val points = arr.map { e ->
                val p = e.takeIf { it.isJsonArray }?.asJsonArray
                    ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "points 须为 [[x,y],...]", null)
                if (p.size() < 2) throw McpToolException(McpErrorCode.INVALID_PARAMS, "每个点须为 [x,y]", null)
                val x = p[0].asInt
                val y = p[1].asInt
                checkPoint(x, y)
                intArrayOf(x, y)
            }.toTypedArray()
            val ok = McpServices.automator().gesture(0, duration, *points)
            if (!ok) throw McpToolException(McpErrorCode.GESTURE_FAILED, "手势未被系统接受", "确认无障碍已开启后重试")
            McpToolResult.json(JsonObject().apply { addProperty("gestured", true) })
        }
}

/** input_key：按键与全局动作（back/home/recents/通知栏，或 keyevent 编码）. */
internal class InputKeyTool : McpTool {
    override val name = "input_key"
    override val description = """
        按键：action 可为 back/home/recents/notifications/quickSettings/splitScreen，或 keycode（配合 code 传 Android keyevent 编码，如 3=HOME 4=BACK 26=POWER）。
        全局动作需无障碍已开启；keycode 走 input 命令。
    """.trimIndent()
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "action" to stringProp("back/home/recents/notifications/quickSettings/splitScreen/keycode"),
            "code" to intProp("action=keycode 时的键值", null, 0, 300),
        ),
        required = listOf("action"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val action = arguments.reqString("action").lowercase()
            val ok = when (action) {
                "back" -> McpServices.automator().back()
                "home" -> McpServices.automator().home()
                "recents" -> McpServices.automator().recents()
                "notifications" -> McpServices.automator().notifications()
                "quicksettings", "quick_settings" -> McpServices.automator().quickSettings()
                "splitscreen", "split_screen" -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                        throw McpToolException(McpErrorCode.UNSUPPORTED_API, "分屏需要 Android 7.0+", null)
                    }
                    McpServices.automator().splitScreen()
                }
                "keycode" -> {
                    val code = arguments.optInt("code", -1)
                    if (code < 0) throw McpToolException(McpErrorCode.INVALID_PARAMS, "action=keycode 时必须传 code", "如 {\"action\":\"keycode\",\"code\":4}")
                    val r = ProcessShell.execCommand("input keyevent $code", false)
                    r.code == 0
                }
                else -> throw McpToolException(
                    McpErrorCode.INVALID_PARAMS,
                    "未知 action：$action",
                    "可用 back/home/recents/notifications/quickSettings/splitScreen/keycode",
                )
            }
            if (!ok) throw McpToolException(McpErrorCode.GESTURE_FAILED, "按键未被系统接受", "确认无障碍已开启后重试")
            McpToolResult.json(JsonObject().apply { addProperty("pressed", true) })
        }
}

/** input_press 语义用 gesture 实现的长按快捷方式（沿用 click/longClick 时长语义）. */
internal class InputLongClickTool : McpTool {
    override val name = "input_long_click"
    override val description = "长按坐标（物理像素），时长沿用系统长按语义。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "x" to intProp("横坐标", null, 0, 10000),
            "y" to intProp("纵坐标", null, 0, 10000),
        ),
        required = listOf("x", "y"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                throw McpToolException(McpErrorCode.UNSUPPORTED_API, "手势需要 Android 7.0+", null)
            }
            val x = arguments.optInt("x", -1)
            val y = arguments.optInt("y", -1)
            checkPoint(x, y)
            val delay = (ViewConfiguration.getLongPressTimeout() + 200).toLong()
            checkGestureTime(delay)
            val ok = McpServices.automator().gesture(0, delay, intArrayOf(x, y))
            if (!ok) throw McpToolException(McpErrorCode.GESTURE_FAILED, "长按未被系统接受", null)
            McpToolResult.json(JsonObject().apply { addProperty("longClicked", true) })
        }
}
