package com.stardust.autojs.core.yolo

import android.graphics.Bitmap
import android.graphics.Rect
import com.stardust.autojs.core.image.ImageWrapper
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * 通用 YOLO 检测器（TFLite CPU 推理）.
 *
 * 模型由用户自备：Ultralytics 导出 `yolo export model=yolo11n.pt format=tflite`
 * （默认 fp32），放到手机存储（如 /sdcard/models/yolo11n.tflite），把路径传给 MCP 的
 * `yolo_detect` 工具. 不内置模型，保持包体积.
 *
 * 输出兼容 YOLOv8/v11 TFLite 的两种排布：[1, 4+C, N] 与 [1, N, 4+C]，
 * 框坐标兼容归一化与输入像素两种尺度（按数值范围自适应）.
 */
class YoloDetector {

    data class Detection(
        val label: String,
        val confidence: Float,
        val bounds: Rect,
    )

    /**
     * @param labels 类名表，缺省 `class_0..N`；长度不足时超出的类回落默认名
     * @throws IllegalArgumentException 模型缺失/格式不支持/参数非法
     */
    fun detect(
        image: ImageWrapper,
        modelPath: String,
        confThreshold: Float = 0.25f,
        iouThreshold: Float = 0.45f,
        labels: List<String>? = null,
        numThreads: Int = 4,
    ): List<Detection> {
        require(confThreshold in 0.01f..1f) { "confThreshold 须在 0.01~1 之间" }
        require(iouThreshold in 0f..1f) { "iouThreshold 须在 0~1 之间" }
        val file = File(modelPath)
        require(file.isFile && file.canRead()) { "模型文件不存在或不可读：$modelPath" }
        val threads = numThreads.coerceIn(1, 8)

        val interpreter = interpreterFor(file, threads)
        val bitmap = image.bitmap
            ?: throw IllegalArgumentException("图片为空")

        synchronized(interpreter) {
            val inTensor = interpreter.getInputTensor(0)
            require(inTensor.dataType() == DataType.FLOAT32) { "只支持 fp32 输入模型，请用 Ultralytics 默认导出" }
            val inShape = inTensor.shape()
            require(inShape.size == 4 && inShape[3] == 3) { "不支持的输入形状：${inShape.contentToString()}" }
            val inputSize = inShape[1]
            require(inputSize == inShape[2] && inputSize in 160..1280) {
                "只支持方形输入（160~1280），当前：${inShape.contentToString()}"
            }

            val input = preprocess(bitmap, inputSize)

            val outTensor = interpreter.getOutputTensor(0)
            val outShape = outTensor.shape()
            require(outShape.size == 3) { "不支持的输出形状：${outShape.contentToString()}" }
            val output = Array(1) { Array(outShape[1]) { FloatArray(outShape[2]) } }
            interpreter.run(input, output)

            return parse(output[0], outShape[1], outShape[2], inputSize,
                bitmap.width, bitmap.height, confThreshold, iouThreshold, labels)
        }
    }

    // ------------------------------------------------------------- 推理

    private fun preprocess(bitmap: Bitmap, inputSize: Int): ByteBuffer {
        val scaled = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val buffer = ByteBuffer.allocateDirect(4 * inputSize * inputSize * 3)
            .order(ByteOrder.nativeOrder())
        val pixels = IntArray(inputSize * inputSize)
        scaled.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)
        if (scaled !== bitmap) scaled.recycle()
        for (pixel in pixels) {
            buffer.putFloat(((pixel shr 16) and 0xFF) / 255f)
            buffer.putFloat(((pixel shr 8) and 0xFF) / 255f)
            buffer.putFloat((pixel and 0xFF) / 255f)
        }
        buffer.rewind()
        return buffer
    }

    private fun parse(
        raw: Array<FloatArray>,
        d1: Int,
        d2: Int,
        inputSize: Int,
        imgW: Int,
        imgH: Int,
        confThreshold: Float,
        iouThreshold: Float,
        labels: List<String>?,
    ): List<Detection> {
        // 排布判定：属性维远小于框数维
        val transposed = d1 < d2
        val attrs = if (transposed) d1 else d2
        val count = if (transposed) d2 else d1
        require(attrs >= 5) { "输出属性维异常（$attrs），非 YOLO 检测模型？" }
        val numClasses = attrs - 4

        fun at(box: Int, a: Int): Float = if (transposed) raw[a][box] else raw[box][a]

        // 先按"输入像素尺度还是归一化"做一次采样判定
        var maxCoord = 0f
        val sample = min(count, 32)
        for (i in 0 until sample) {
            maxCoord = max(maxCoord, max(at(i, 0), at(i, 1)))
        }
        val inPixels = maxCoord > 1.5f

        val candidates = ArrayList<YoloBox>()
        for (i in 0 until count) {
            var bestCls = -1
            var bestScore = 0f
            for (c in 0 until numClasses) {
                val s = at(i, 4 + c)
                if (s > bestScore) {
                    bestScore = s
                    bestCls = c
                }
            }
            if (bestCls < 0 || bestScore < confThreshold) continue
            var cx = at(i, 0)
            var cy = at(i, 1)
            var w = at(i, 2)
            var h = at(i, 3)
            if (inPixels) {
                cx /= inputSize; cy /= inputSize; w /= inputSize; h /= inputSize
            }
            val l = ((cx - w / 2) * imgW).coerceIn(0f, imgW.toFloat())
            val t = ((cy - h / 2) * imgH).coerceIn(0f, imgH.toFloat())
            val r = ((cx + w / 2) * imgW).coerceIn(0f, imgW.toFloat())
            val b = ((cy + h / 2) * imgH).coerceIn(0f, imgH.toFloat())
            if (r - l < 2 || b - t < 2) continue
            candidates.add(YoloBox(bestCls, bestScore, l, t, r, b))
        }
        // 按类分别 NMS
        val kept = ArrayList<YoloBox>()
        candidates.groupBy { it.cls }.values.forEach { group ->
            kept.addAll(nms(group.sortedByDescending { it.score }, iouThreshold))
        }
        return kept.sortedByDescending { it.score }.map { b ->
            val name = labels?.getOrNull(b.cls) ?: "class_${b.cls}"
            Detection(name, b.score, Rect(b.l.toInt(), b.t.toInt(), b.r.toInt(), b.b.toInt()))
        }
    }

    private data class YoloBox(
        val cls: Int,
        val score: Float,
        val l: Float,
        val t: Float,
        val r: Float,
        val b: Float,
    )

    /** 同类内按分数降序贪心 NMS，输入须已按 score 降序. */
    private fun nms(sorted: List<YoloBox>, iouThreshold: Float): List<YoloBox> {
        val kept = ArrayList<YoloBox>()
        val suppressed = BooleanArray(sorted.size)
        for (i in sorted.indices) {
            if (suppressed[i]) continue
            val a = sorted[i]
            kept.add(a)
            for (j in i + 1 until sorted.size) {
                if (!suppressed[j] && iou(a, sorted[j]) > iouThreshold) {
                    suppressed[j] = true
                }
            }
        }
        return kept
    }

    private fun iou(a: YoloBox, b: YoloBox): Float {
        val interL = max(a.l, b.l)
        val interT = max(a.t, b.t)
        val interR = min(a.r, b.r)
        val interB = min(a.b, b.b)
        val inter = max(0f, interR - interL) * max(0f, interB - interT)
        if (inter <= 0f) return 0f
        val union = (a.r - a.l) * (a.b - a.t) + (b.r - b.l) * (b.b - b.t) - inter
        return if (union <= 0f) 0f else inter / union
    }

    // ------------------------------------------------------------- 模型缓存

    private data class Cached(val mtime: Long, val size: Long, val threads: Int, val interpreter: Interpreter)

    private val cache = HashMap<String, Cached>()

    private fun interpreterFor(file: File, threads: Int): Interpreter {
        synchronized(cache) {
            val hit = cache[file.absolutePath]
            if (hit != null && hit.mtime == file.lastModified() && hit.size == file.length() && hit.threads == threads) {
                return hit.interpreter
            }
            hit?.interpreter?.close()
            val options = Interpreter.Options().apply { setNumThreads(threads) }
            val buffer = FileInputStream(file).channel.use { ch: FileChannel ->
                ch.map(FileChannel.MapMode.READ_ONLY, 0, file.length())
            }
            val interpreter = Interpreter(buffer, options)
            cache[file.absolutePath] = Cached(file.lastModified(), file.length(), threads, interpreter)
            return interpreter
        }
    }

    companion object {
        @Volatile
        private var instance: YoloDetector? = null

        fun get(): YoloDetector =
            instance ?: synchronized(this) { instance ?: YoloDetector().also { instance = it } }
    }
}
