package org.autojs.autojs.mcp.tools

import android.graphics.Bitmap
import android.util.Base64
import com.stardust.autojs.core.image.ImageWrapper
import org.autojs.autojs.mcp.McpConfig
import java.io.ByteArrayOutputStream

/** Bitmap 缩放 + JPEG 压到上限内的编码工具. */
internal object McpImages {

    data class Encoded(val base64: String, val mimeType: String, val width: Int, val height: Int)

    fun encode(wrapper: ImageWrapper, maxWidth: Int = McpConfig.DEFAULT_CAPTURE_MAX_WIDTH): Encoded {
        var bitmap = wrapper.bitmap
        if (maxWidth > 0 && bitmap.width > maxWidth) {
            val scale = maxWidth.toFloat() / bitmap.width
            val h = (bitmap.height * scale).toInt().coerceAtLeast(1)
            bitmap = Bitmap.createScaledBitmap(bitmap, maxWidth, h, true)
        }
        var quality = 85
        var bytes = compress(bitmap, quality)
        // 超过上限则降画质重试，避免撞客户端消息上限.
        while (bytes.size > McpConfig.MAX_IMAGE_BYTES && quality > 30) {
            quality -= 15
            bytes = compress(bitmap, quality)
        }
        if (bytes.size > McpConfig.MAX_IMAGE_BYTES) {
            throw McpToolException(
                McpErrorCode.INTERNAL,
                "截图 ${bytes.size} 字节超过上限 ${McpConfig.MAX_IMAGE_BYTES}，请调小 maxWidth 后重试",
                "例如传 {\"maxWidth\": 720}",
            )
        }
        return Encoded(
            base64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
            mimeType = "image/jpeg",
            width = bitmap.width,
            height = bitmap.height,
        )
    }

    private fun compress(bitmap: Bitmap, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.flush()
        return out.toByteArray()
    }
}
