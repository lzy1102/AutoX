package org.autojs.autojs.mcp.tools

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.autojs.autojs.mcp.McpCallContext

/** app_list：列出已安装应用. */
internal class AppListTool : McpTool {
    override val name = "app_list"
    override val description = "列出已安装应用（名称/包名/版本），可用 keyword 过滤、includeSystem 决定是否含系统应用。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "keyword" to stringProp("按应用名或包名过滤（不区分大小写）"),
            "includeSystem" to boolProp("是否包含系统应用", false),
            "limit" to intProp("最多返回个数", 100, 1, 500),
        ),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val keyword = arguments.optString("keyword", null)?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
            val includeSystem = arguments.optBoolean("includeSystem", false)
            val limit = arguments.optInt("limit", 100).coerceIn(1, 500)
            val pm = McpServices.context.packageManager
            val installed = try {
                withTimeout(30_000) {
                    pm.getInstalledApplications(PackageManager.GET_META_DATA)
                }
            } catch (e: TimeoutCancellationException) {
                throw McpToolException(McpErrorCode.TIMEOUT, "获取应用列表超时", "请重试")
            }
            val matched = installed.asSequence()
                .filter { includeSystem || !isSystemApp(it) }
                .mapNotNull { info ->
                    val label = runCatching { pm.getApplicationLabel(info).toString() }.getOrDefault(info.packageName)
                    if (keyword != null &&
                        !label.lowercase().contains(keyword) &&
                        !info.packageName.lowercase().contains(keyword)
                    ) {
                        null
                    } else {
                        label to info
                    }
                }
                .sortedBy { it.first }
                .toList()
            val returned = matched.take(limit)
            val arr = JsonArray()
            returned.forEach { (label, info) ->
                arr.add(JsonObject().apply {
                    addProperty("appName", label)
                    addProperty("packageName", info.packageName)
                    addProperty("system", isSystemApp(info))
                    runCatching {
                        pm.getPackageInfo(info.packageName, 0).versionName
                    }.getOrNull()?.let { addProperty("versionName", it) }
                })
            }
            McpToolResult.json(JsonObject().apply {
                addProperty("count", matched.size)
                addProperty("returned", arr.size())
                addProperty("truncated", matched.size > arr.size())
                add("apps", arr)
            })
        }

    private fun isSystemApp(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0
}
