package org.autojs.autojs.mcp.tools

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.automator.UiObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.autojs.autojs.mcp.McpCallContext

private fun selectorSchema(): Map<String, JsonObject> = mapOf(
    "text" to stringProp("精确文本"),
    "textContains" to stringProp("包含文本"),
    "textMatches" to stringProp("文本正则"),
    "desc" to stringProp("精确描述"),
    "descContains" to stringProp("包含描述"),
    "descMatches" to stringProp("描述正则"),
    "id" to stringProp("控件 id（可省略包名前缀）"),
    "idContains" to stringProp("id 包含"),
    "className" to stringProp("类名"),
    "packageName" to stringProp("包名"),
    "clickable" to boolProp("是否可点击"),
    "scrollable" to boolProp("是否可滚动"),
    "editable" to boolProp("是否可编辑"),
    "checkable" to boolProp("是否可勾选"),
    "depth" to intProp("层级", null, 0, 30),
    "timeoutMs" to intProp("等待超时毫秒", 3000, 0, 10000),
)

private fun nodeToJson(node: UiObject, maxChildren: Int = 50): JsonObject {
    val bounds: Rect = runCatching { node.bounds() }.getOrDefault(Rect())
    return JsonObject().apply {
        addProperty("text", runCatching { node.text() }.getOrDefault(""))
        runCatching { node.desc() }?.getOrNull()?.let { addProperty("desc", it) }
        runCatching { node.id() }?.getOrNull()?.let { addProperty("id", it) }
        runCatching { node.className() }?.getOrNull()?.let { addProperty("className", it) }
        runCatching { node.packageName() }?.getOrNull()?.let { addProperty("packageName", it) }
        add("bounds", JsonObject().apply {
            addProperty("left", bounds.left)
            addProperty("top", bounds.top)
            addProperty("right", bounds.right)
            addProperty("bottom", bounds.bottom)
            addProperty("centerX", bounds.centerX())
            addProperty("centerY", bounds.centerY())
        })
        addProperty("clickable", runCatching { node.isClickable }.getOrDefault(false))
        addProperty("scrollable", runCatching { node.isScrollable }.getOrDefault(false))
        addProperty("editable", runCatching { node.isEditable }.getOrDefault(false))
        addProperty("checkable", runCatching { node.isCheckable }.getOrDefault(false))
        addProperty("checked", runCatching { node.isChecked }.getOrDefault(false))
        addProperty("enabled", runCatching { node.isEnabled }.getOrDefault(false))
        addProperty("depth", runCatching { node.depth() }.getOrDefault(0))
    }
}

/** ui_find：按选择器查找控件，返回命中列表（含 bounds 供点击）. */
internal class UiFindTool : McpTool {
    override val name = "ui_find"
    override val description = "按文本/描述/id/类名查找控件，返回文本与屏幕坐标。至少传一个选择器条件，找不到返回空数组。"
    override val inputSchema: JsonObject = schemaOf(
        selectorSchema() + mapOf("limit" to intProp("最多返回个数", 10, 1, 50)),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val sel = McpServices.selectorFrom(arguments)
            val timeout = arguments.optLong("timeoutMs", 3000L).coerceIn(0L, 10_000L)
            val limit = arguments.optInt("limit", 10).coerceIn(1, 50)
            if (timeout > 0) {
                // findOne 轮询等待首个命中，再全量拉取.
                sel.findOne(timeout)
            }
            val collection = sel.find()
            val found = (0 until collection.size()).mapNotNull { collection[it] }.take(limit)
            val arr = JsonArray().apply { found.forEach { add(nodeToJson(it)) } }
            McpToolResult.json(JsonObject().apply {
                addProperty("count", arr.size())
                add("nodes", arr)
            })
        }
}

/** ui_click：查找并点击第一个命中的控件. */
internal class UiClickTool : McpTool {
    override val name = "ui_click"
    override val description = "查找控件并点击第一个命中。需要无障碍服务已开启；点击无反馈时改用 input_tap 按 bounds 中心点。"
    override val inputSchema: JsonObject = schemaOf(selectorSchema())

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val sel = McpServices.selectorFrom(arguments)
            val timeout = arguments.optLong("timeoutMs", 3000L).coerceIn(0L, 10_000L)
            val node = sel.findOne(timeout)
                ?: throw McpToolException(McpErrorCode.NODE_NOT_FOUND, "未找到匹配的控件", "先用 ui_find 确认选择器，或调大 timeoutMs")
            val ok = runCatching { node.click() }.getOrDefault(false)
            if (!ok) {
                throw McpToolException(McpErrorCode.GESTURE_FAILED, "控件点击无响应", "改用 input_tap 按 bounds 中心点击")
            }
            McpToolResult.json(JsonObject().apply {
                addProperty("clicked", true)
                add("node", nodeToJson(node))
            })
        }
}

/** ui_input：查找可编辑控件并输入文本. */
internal class UiInputTool : McpTool {
    override val name = "ui_input"
    override val description = "查找输入框并设置文本。默认先清空再输入；需要无障碍服务已开启。"
    override val inputSchema: JsonObject = schemaOf(
        selectorSchema() + mapOf("content" to stringProp("要输入的文本")),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val content = arguments.optString("content", "") ?: ""
            val sel = McpServices.selectorFrom(arguments)
            val timeout = arguments.optLong("timeoutMs", 3000L).coerceIn(0L, 10_000L)
            val node = sel.findOne(timeout)
                ?: throw McpToolException(McpErrorCode.NODE_NOT_FOUND, "未找到匹配的输入框", "先用 ui_find 确认选择器")
            val ok = runCatching { node.setText(content) }.getOrDefault(false)
            if (!ok) {
                throw McpToolException(McpErrorCode.GESTURE_FAILED, "输入失败（控件可能不可编辑）", "确认控件 editable 后重试")
            }
            McpToolResult.json(JsonObject().apply {
                addProperty("input", true)
                add("node", nodeToJson(node))
            })
        }
}

/** ui_dump：导出当前窗口控件树（裁剪版，便于定位）. */
internal class UiDumpTool : McpTool {
    override val name = "ui_dump"
    override val description = "导出当前前台窗口的控件树（文本/描述/bounds），用于定位选择器。节点过多时按 maxNodes 截断。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf("maxNodes" to intProp("最多返回节点数", 120, 10, 500)),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            McpServices.requireService()
            val maxNodes = arguments.optInt("maxNodes", 120).coerceIn(10, 500)
            val roots = McpServices.bridge.windowRoots()
            if (roots.isEmpty()) {
                throw McpToolException(McpErrorCode.NO_FOREGROUND_ACTIVITY, "取不到窗口根节点", "确认前台有 Activity 且无障碍已开启")
            }
            val count = intArrayOf(0)
            val arr = JsonArray()
            for (root in roots) {
                root ?: continue
                dumpNode(root, 0, maxNodes, count, arr)
                if (count[0] >= maxNodes) break
            }
            McpToolResult.json(JsonObject().apply {
                addProperty("count", count[0])
                addProperty("truncated", count[0] >= maxNodes)
                add("nodes", arr)
            })
        }

    private fun dumpNode(
        info: AccessibilityNodeInfo,
        depth: Int,
        maxNodes: Int,
        count: IntArray,
        out: JsonArray,
    ) {
        if (count[0] >= maxNodes || depth > 20) return
        val bounds = android.graphics.Rect()
        runCatching { info.getBoundsInScreen(bounds) }
        val obj = JsonObject().apply {
            addProperty("depth", depth)
            info.text?.toString()?.takeIf { it.isNotEmpty() }?.let { addProperty("text", it) }
            info.contentDescription?.toString()?.takeIf { it.isNotEmpty() }?.let { addProperty("desc", it) }
            info.viewIdResourceName?.takeIf { it.isNotEmpty() }?.let { addProperty("id", it) }
            info.className?.toString()?.let { addProperty("className", it) }
            add("bounds", JsonObject().apply {
                addProperty("left", bounds.left)
                addProperty("top", bounds.top)
                addProperty("right", bounds.right)
                addProperty("bottom", bounds.bottom)
                addProperty("centerX", bounds.centerX())
                addProperty("centerY", bounds.centerY())
            })
            if (info.isClickable) addProperty("clickable", true)
            if (info.isScrollable) addProperty("scrollable", true)
            if (info.isEditable) addProperty("editable", true)
        }
        out.add(obj)
        count[0]++
        for (i in 0 until info.childCount) {
            if (count[0] >= maxNodes) break
            val child = runCatching { info.getChild(i) }.getOrNull() ?: continue
            dumpNode(child, depth + 1, maxNodes, count, out)
            runCatching { child.recycle() }
        }
    }
}
