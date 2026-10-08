package com.stardust.autojs.runtime.api

import com.stardust.autojs.core.image.ImageWrapper
import com.stardust.autojs.core.yolo.YoloDetector
import com.stardust.autojs.runtime.ScriptRuntime

/**
 * 脚本侧 YOLO API（JS 全局对象 `yolo`）.
 *
 * 用法与 `images.*` 一致：不传 image 时自动截屏（需先 `images.requestScreenCapture()`），
 * 模型为 Ultralytics 导出的 fp32 TFLite，路径传手机存储绝对路径.
 */
class Yolo(private val mRuntime: ScriptRuntime) {

    /**
     * @param image images.captureScreen() 等返回的图片对象，传 null/缺省则现截一屏
     * @param modelPath .tflite 绝对路径，如 /sdcard/models/yolo11n.tflite
     * @param labels 逗号分隔类名表，缺省 class_0..N
     */
    @JvmOverloads
    fun detect(
        image: Any?,
        modelPath: String,
        labels: String? = null,
        confThreshold: Double = 0.25,
        iouThreshold: Double = 0.45,
        numThreads: Int = 4,
    ): List<YoloDetector.Detection> {
        val wrapper = toImageWrapper(image) ?: captureScreen()
        val captured = wrapper !== image
        try {
            val labelList = labels
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.takeIf { it.isNotEmpty() }
            return YoloDetector.get().detect(
                wrapper,
                modelPath,
                confThreshold.toFloat(),
                iouThreshold.toFloat(),
                labelList,
                numThreads,
            )
        } finally {
            if (captured) {
                runCatching { wrapper.recycle() }
            }
        }
    }

    private fun toImageWrapper(image: Any?): ImageWrapper? = when (image) {
        null -> null
        is ImageWrapper -> image
        else -> throw IllegalArgumentException(
            "image 参数须为 images.captureScreen() 等返回的图片对象或 null"
        )
    }

    private fun captureScreen(): ImageWrapper =
        (mRuntime.images as Images).captureScreen()
}
