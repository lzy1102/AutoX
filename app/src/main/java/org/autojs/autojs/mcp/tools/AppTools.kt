package org.autojs.autojs.mcp.tools

import android.content.Intent
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.autojs.autojs.mcp.McpCallContext

/** app_launch：按包名或应用名启动. */
internal class AppLaunchTool : McpTool {
    override val name = "app_launch"
    override val description = "启动应用：传 packageName（如 com.tencent.mm）或 appName（如 微信，二者传一即可）。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "packageName" to stringProp("包名"),
            "appName" to stringProp("应用显示名"),
        ),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val appUtils = McpServices.autoJs().appUtils
            val pkg = arguments.optString("packageName", null)?.trim()?.takeIf { it.isNotEmpty() }
            val appName = arguments.optString("appName", null)?.trim()?.takeIf { it.isNotEmpty() }
            val ok = when {
                pkg != null -> appUtils.launchPackage(pkg)
                appName != null -> appUtils.launchApp(appName)
                else -> throw McpToolException(McpErrorCode.INVALID_PARAMS, "需传 packageName 或 appName", null)
            }
            if (!ok) {
                throw McpToolException(McpErrorCode.INVALID_PARAMS, "启动失败：应用未找到", "用 app_current 确认包名后重试")
            }
            McpToolResult.json(JsonObject().apply {
                addProperty("launched", true)
                pkg?.let { addProperty("packageName", it) }
                appName?.let { addProperty("appName", it) }
            })
        }
}

/** app_current：当前前台包名与 Activity. */
internal class AppCurrentTool : McpTool {
    override val name = "app_current"
    override val description = "查询当前前台应用包名与 Activity（无障碍/用量统计/无障碍事件三路，以设备实际可用为准）。"
    override val inputSchema: JsonObject = schemaOf(emptyMap())

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val info = McpServices.autoJs().infoProvider
            val pkg = runCatching { info.latestPackage }.getOrNull()
            val activity = runCatching { info.latestActivity }.getOrNull()
            McpToolResult.json(JsonObject().apply {
                pkg?.let { addProperty("packageName", it) }
                activity?.let { addProperty("activity", it) }
                if (pkg == null) addProperty("empty", true)
            })
        }
}

/** app_open_url：用系统 VIEW 打开链接或跳转应用设置. */
internal class AppOpenUrlTool : McpTool {
    override val name = "app_open_url"
    override val description = "用 ACTION_VIEW 打开 url（如 https 链接或应用内 scheme）。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf("url" to stringProp("要打开的链接")),
        required = listOf("url"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val url = arguments.reqString("url")
            try {
                val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                McpServices.context.startActivity(intent)
            } catch (e: Exception) {
                throw McpToolException(McpErrorCode.INVALID_PARAMS, "打开失败：${e.localizedMessage}", "确认 url 合法且有可处理的应用")
            }
            McpToolResult.json(JsonObject().apply { addProperty("opened", true) })
        }
}
