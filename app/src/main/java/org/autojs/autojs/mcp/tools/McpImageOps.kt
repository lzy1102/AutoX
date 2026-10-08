package org.autojs.autojs.mcp.tools

import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.opencv.OpenCVHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.opencv.core.Rect

/**
 * 找图找色共享逻辑：OpenCV 初始化、图片来源、区域与颜色解析.
 *
 * 坐标一律物理像素：截图即物理分辨率，[ColorFinder] 配全新 [ScreenMetrics]（design=0）
 * 时缩放为恒等变换，[TemplateMatching] 本就不做缩放，因此结果无需换算.
 */
internal object McpImageOps {

    suspend fun ensureOpenCv() {
        if (OpenCVHelper.isInitialized()) return
        withContext(Dispatchers.IO) {
            OpenCVHelper.initIfNeeded(McpServices.context) {}
        }
        if (!OpenCVHelper.isInitialized()) {
            throw McpToolException(McpErrorCode.INTERNAL, "OpenCV 初始化失败", "请重试")
        }
    }

    /** 图片来源：传 image（base64）就用它，否则现截一屏. 调用方负责 recycle. */
    suspend fun sourceImage(args: JsonObject): ImageWrapper {
        val base64 = args.optString("image", null)?.trim()?.takeIf { it.isNotEmpty() }
        return if (base64 != null) decodeImage(base64, "image") else McpServices.captureImage()
    }

    fun decodeImage(base64: String, what: String = "图片"): ImageWrapper {
        val bytes = try {
            Base64.decode(base64, Base64.DEFAULT)
        } catch (e: Exception) {
            throw McpToolException(McpErrorCode.INVALID_IMAGE, "$what 不是合法 base64", "请传 PNG/JPEG 的 base64")
        }
        if (bytes.size > 20 * 1024 * 1024) {
            throw McpToolException(McpErrorCode.INVALID_IMAGE, "$what 过大（${bytes.size} 字节）", "请压缩后重试")
        }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            ?: throw McpToolException(McpErrorCode.INVALID_IMAGE, "$what 解码失败", "请传 PNG/JPEG 的 base64")
        if (bitmap.width > 4096 || bitmap.height > 4096) {
            bitmap.recycle()
            throw McpToolException(McpErrorCode.INVALID_IMAGE, "$what 尺寸过大（${bitmap.width}x${bitmap.height}）", "请缩放到 4096 以内")
        }
        return ImageWrapper.ofBitmap(bitmap)
            ?: throw McpToolException(McpErrorCode.INVALID_IMAGE, "$what 封装失败", "请重试")
    }

    /** region ``{left,top,right,bottom}``，缺省全图；返回 null 表示全图. */
    fun parseRegion(args: JsonObject, w: Int, h: Int): Rect? {
        val obj = args.get("region")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val l = obj.optInt("left", 0).coerceIn(0, w)
        val t = obj.optInt("top", 0).coerceIn(0, h)
        val r = obj.optInt("right", w).coerceIn(0, w)
        val b = obj.optInt("bottom", h).coerceIn(0, h)
        if (l >= r || t >= b) {
            throw McpToolException(McpErrorCode.INVALID_PARAMS, "region 非法：[$l,$t,$r,$b]", "需满足 left<right、top<bottom 且在图内")
        }
        if (l == 0 && t == 0 && r == w && b == h) return null
        return Rect(l, t, r - l, b - t)
    }

    fun regionSchema(): JsonObject = JsonObject().apply {
        addProperty("type", "object")
        addProperty("description", "搜索区域（物理像素），缺省全图")
        add("properties", JsonObject().apply {
            add("left", intProp("左", 0, 0, 10000))
            add("top", intProp("上", 0, 0, 10000))
            add("right", intProp("右", null, 0, 10000))
            add("bottom", intProp("下", null, 0, 10000))
        })
        addProperty("additionalProperties", false)
    }

    /** 颜色支持 "#RRGGBB" / "#AARRGGBB" / "0xAARRGGBB" / 十进制整数. */
    fun parseColor(raw: String): Int {
        val s = raw.trim()
        if (s.isEmpty()) throw McpToolException(McpErrorCode.INVALID_PARAMS, "颜色为空", "如 \"#FF0000\"")
        try {
            if (s.startsWith("#")) return Color.parseColor(s)
        } catch (_: Exception) {
            throw McpToolException(McpErrorCode.INVALID_PARAMS, "颜色格式错误：$s", "如 \"#FF0000\"")
        }
        try {
            val v = if (s.startsWith("0x", ignoreCase = true)) s.substring(2).toLong(16)
            else s.toLong()
            return v.toInt()
        } catch (_: Exception) {
            throw McpToolException(McpErrorCode.INVALID_PARAMS, "颜色格式错误：$s", "如 \"#FF0000\"")
        }
    }

    fun parseColors(arr: JsonArray, max: Int = 10): IntArray {
        if (arr.size() == 0) throw McpToolException(McpErrorCode.INVALID_PARAMS, "colors 至少1个颜色", null)
        if (arr.size() > max) throw McpToolException(McpErrorCode.INVALID_PARAMS, "colors 最多$max 个", "请分批调用")
        return IntArray(arr.size()) { i ->
            val e = arr[i]
            if (!e.isJsonPrimitive) throw McpToolException(McpErrorCode.INVALID_PARAMS, "colors[$i] 须为颜色串", "如 \"#FF0000\"")
            parseColor(e.asString)
        }
    }

    fun checkDir(dir: Int): Int {
        if (dir !in 0..4) throw McpToolException(McpErrorCode.INVALID_PARAMS, "dir 须为 0~4", "0=左上→右下 1=中心向外 2=右下→左上 3=左下→右上 4=右上→左下")
        return dir
    }

    fun checkTolerance(t: Int): Int {
        if (t !in 0..255) throw McpToolException(McpErrorCode.INVALID_PARAMS, "tolerance 须为 0~255", "常用 4~16")
        return t
    }

    fun pointJson(x: Int, y: Int): JsonObject = JsonObject().apply {
        addProperty("x", x)
        addProperty("y", y)
    }
}
