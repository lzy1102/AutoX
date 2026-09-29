package org.autojs.autojs.mcp.tools

import android.content.Context
import com.google.gson.JsonObject
import com.stardust.app.GlobalAppContext
import com.stardust.autojs.core.accessibility.AccessibilityBridge
import com.stardust.autojs.core.accessibility.UiSelector
import com.stardust.autojs.core.activity.ActivityInfoProvider
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.image.capture.ScreenCaptureManager
import com.stardust.autojs.core.image.capture.ScreenCapturer
import com.stardust.autojs.runtime.accessibility.AccessibilityConfig
import com.stardust.automator.GlobalActionAutomator
import com.stardust.view.accessibility.AccessibilityNotificationObserver
import com.stardust.view.accessibility.AccessibilityService
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import org.autojs.autojs.autojs.AutoJs
import org.autojs.autojs.mcp.McpConfig

/**
 * MCP 工具共享的原生能力入口.
 *
 * 不依赖脚本引擎：直接复用 [AutoJs] 单例中的 infoProvider / appUtils / layoutInspector，
 * 无障碍走自建的轻量 [AccessibilityBridge]（service 取 [AccessibilityService.instance]），
 * 截图走自持的 [ScreenCaptureManager]（与脚本侧互不干扰）.
 */
internal object McpServices {

    val context: Context get() = GlobalAppContext.get()

    fun autoJs(): AutoJs = AutoJs.getInstance()
        ?: throw McpToolException(McpErrorCode.INTERNAL, "AutoJs 未初始化", "请重启应用后重试")

    val bridge: AccessibilityBridge by lazy {
        val ctx = context
        val autoJs = autoJs()
        object : AccessibilityBridge(ctx, AccessibilityConfig(), autoJs.uiHandler) {
            override fun ensureServiceEnabled() {
                requireService()
            }

            override fun waitForServiceEnabled() {
                requireService()
            }

            override fun getService(): AccessibilityService? = AccessibilityService.instance

            override fun getInfoProvider(): ActivityInfoProvider = autoJs.infoProvider

            override fun getNotificationObserver(): AccessibilityNotificationObserver {
                throw UnsupportedOperationException("MCP 不支持通知监听")
            }
        }
    }

    fun requireService(): AccessibilityService =
        AccessibilityService.instance
            ?: throw McpToolException(
                McpErrorCode.NO_ACCESSIBILITY_SERVICE,
                "无障碍服务未开启",
                "请先在手机上开启 AutoX 的无障碍服务后重试",
            )

    fun automator(): GlobalActionAutomator {
        val service = requireService()
        // handler 传 null 走无 Handler 分支（自建 Looper 等回调），避免依赖脚本线程的 Handler.
        return GlobalActionAutomator(null) { service }
    }

    // ---------------------------------------------------------- 截图

    private val captureManager = ScreenCaptureManager()

    val isCaptureAvailable: Boolean get() = captureManager.screenCapture?.available == true

    suspend fun ensureCapture(): ScreenCapturer {
        captureManager.screenCapture?.takeIf { it.available }?.let { return it }
        try {
            withTimeout(McpConfig.CAPTURE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    captureManager.requestScreenCapture(context, ScreenCapturer.ORIENTATION_AUTO)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw McpToolException(
                McpErrorCode.TIMEOUT,
                "等待录屏授权超时（${McpConfig.CAPTURE_TIMEOUT_MS}ms）",
                "请在手机上确认录屏授权后重试",
            )
        } catch (e: McpToolException) {
            throw e
        } catch (e: Exception) {
            throw McpToolException(
                McpErrorCode.SCREEN_CAPTURE_NOT_GRANTED,
                "未获得录屏权限：${e.localizedMessage}",
                "首次调用需要在手机上手动确认录屏授权",
            )
        }
        return captureManager.screenCapture?.takeIf { it.available }
            ?: throw McpToolException(
                McpErrorCode.SCREEN_CAPTURE_NOT_GRANTED,
                "未获得录屏权限",
                "首次调用需要在手机上手动确认录屏授权",
            )
    }

    suspend fun captureImage(): ImageWrapper {
        val capturer = ensureCapture()
        try {
            return withTimeout(McpConfig.CAPTURE_TIMEOUT_MS) {
                withContext(Dispatchers.IO) { capturer.captureImageWrapper() }
            }
        } catch (e: TimeoutCancellationException) {
            throw McpToolException(McpErrorCode.TIMEOUT, "截图超时", "请重试")
        }
    }

    // ---------------------------------------------------------- 选择器

    /** 从 arguments 构造 UiSelector；未传任何条件时抛参数错误，避免全量扫描. */
    fun selectorFrom(args: JsonObject): UiSelector {
        val sel = UiSelector(bridge)
        var hasCondition = false

        args.optString("text", null)?.let { sel.text(it); hasCondition = true }
        args.optString("textContains", null)?.let { sel.textContains(it); hasCondition = true }
        args.optString("textMatches", null)?.let { sel.textMatches(it); hasCondition = true }
        args.optString("desc", null)?.let { sel.desc(it); hasCondition = true }
        args.optString("descContains", null)?.let { sel.descContains(it); hasCondition = true }
        args.optString("descMatches", null)?.let { sel.descMatches(it); hasCondition = true }
        args.optString("id", null)?.let { sel.id(it); hasCondition = true }
        args.optString("idContains", null)?.let { sel.idContains(it); hasCondition = true }
        args.optString("className", null)?.let { sel.className(it); hasCondition = true }
        args.optString("packageName", null)?.let { sel.packageName(it); hasCondition = true }

        if (args.has("clickable")) {
            sel.clickable(args.optBoolean("clickable", true)); hasCondition = true
        }
        if (args.has("scrollable")) {
            sel.scrollable(args.optBoolean("scrollable", true)); hasCondition = true
        }
        if (args.has("editable")) {
            sel.editable(args.optBoolean("editable", true)); hasCondition = true
        }
        if (args.has("checkable")) {
            sel.checkable(args.optBoolean("checkable", true)); hasCondition = true
        }
        if (args.has("depth")) {
            sel.depth(args.optInt("depth", 0)); hasCondition = true
        }

        if (!hasCondition) {
            throw McpToolException(
                McpErrorCode.INVALID_PARAMS,
                "至少需要一个选择器条件（text/textContains/id/desc/className 等）",
                "例如传 {\"text\": \"确定\"}",
            )
        }
        return sel
    }
}
