package org.autojs.autojs.mcp.tools

import android.os.Build
import com.google.gson.JsonObject
import com.stardust.autojs.runtime.api.Device
import com.stardust.util.ScreenMetrics
import com.stardust.view.accessibility.AccessibilityService
import org.autojs.autojs.mcp.McpCallContext
import org.autojs.autojs.tool.AccessibilityServiceTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** device_status：调用其他工具前的环境自检. */
internal class DeviceStatusTool : McpTool {
    override val name = "device_status"
    override val description = """
        查询设备与服务状态。使用前先调用：
        - accessibilityServiceEnabled=false 时，所有 ui_* 与 input_key 全局动作不可用；
        - captureAvailable=false 时，screen_capture/ocr 首次调用需在手机上确认录屏授权。
    """.trimIndent()
    override val inputSchema: JsonObject = schemaOf(emptyMap())

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val appContext = McpServices.context
            val accessibilityEnabled = runCatching {
                AccessibilityServiceTool.isAccessibilityServiceEnabled(appContext)
            }.getOrDefault(false)
            val serviceAlive = AccessibilityService.instance != null
            val captureAvailable = McpServices.isCaptureAvailable
            val device = Device(appContext)
            val battery = runCatching { device.battery }.getOrDefault(-1f)
            val screenOn = runCatching { device.isScreenOn }.getOrDefault(true)
            val foreground = runCatching {
                val info = McpServices.autoJs().infoProvider
                info.latestPackage to info.latestActivity
            }.getOrNull()
            val foregroundPackage = foreground?.first
            val foregroundActivity = foreground?.second

            val structured = JsonObject().apply {
                addProperty("accessibilityServiceEnabled", accessibilityEnabled)
                addProperty("accessibilityServiceAlive", serviceAlive)
                addProperty("captureAvailable", captureAvailable)
                foregroundPackage?.let { addProperty("foregroundPackage", it) }
                foregroundActivity?.let { addProperty("foregroundActivity", it) }
                addProperty("screenWidth", ScreenMetrics.getDeviceScreenWidth())
                addProperty("screenHeight", ScreenMetrics.getDeviceScreenHeight())
                addProperty("battery", battery)
                addProperty("screenOn", screenOn)
                addProperty("sdkInt", Build.VERSION.SDK_INT)
                addProperty("model", Build.MODEL)
            }
            McpToolResult.json(structured)
        }
}
