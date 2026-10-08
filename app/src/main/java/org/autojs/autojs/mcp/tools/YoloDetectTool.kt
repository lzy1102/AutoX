package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.autojs.core.yolo.YoloDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.autojs.autojs.mcp.McpCallContext
import org.autojs.autojs.mcp.McpConfig

/** yolo_detect：用自备 TFLite 模型做目标检测（找怪/找按钮/验证码目标等）. */
internal class YoloDetectTool : McpTool {
    override val name = "yolo_detect"
    override val description = """
        用设备上的 TFLite 检测模型找目标，返回标签/置信度/边框（物理像素）。
        模型自备：Ultralytics 导出 fp32（yolo export model=xxx.pt format=tflite），放到手机存储，
        传 modelPath（如 /sdcard/models/yolo11n.tflite）。labels 缺省为 class_0..N。
        首次加载模型约几百毫秒，之后走缓存。
    """.trimIndent()
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "modelPath" to stringProp("手机上的 .tflite 模型绝对路径"),
            "image" to stringProp("待检测图 base64，缺省现截一屏"),
            "confThreshold" to JsonObject().apply {
                addProperty("type", "number")
                addProperty("description", "置信度阈值")
                addProperty("default", 0.25)
                addProperty("minimum", 0.01)
                addProperty("maximum", 1)
            },
            "iouThreshold" to JsonObject().apply {
                addProperty("type", "number")
                addProperty("description", "NMS 的 IoU 阈值")
                addProperty("default", 0.45)
                addProperty("minimum", 0)
                addProperty("maximum", 1)
            },
            "labels" to JsonObject().apply {
                addProperty("type", "array")
                addProperty("description", "类名表（按输出类别顺序），缺省 class_0..N")
            },
            "numThreads" to intProp("CPU 线程数", 4, 1, 8),
        ),
        required = listOf("modelPath"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val modelPath = arguments.reqString("modelPath")
        val conf = arguments.optDouble("confThreshold", 0.25).coerceIn(0.01, 1.0).toFloat()
        val iou = arguments.optDouble("iouThreshold", 0.45).coerceIn(0.0, 1.0).toFloat()
        val threads = arguments.optInt("numThreads", 4).coerceIn(1, 8)
        val labels = arguments.get("labels")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { if (it.isJsonPrimitive) it.asString else null }
            ?.takeIf { it.isNotEmpty() }
        val source = McpImageOps.sourceImage(arguments)
        try {
            val detections = try {
                withTimeout(McpConfig.MATCH_TIMEOUT_MS) {
                    withContext(Dispatchers.IO) {
                        YoloDetector.get().detect(source, modelPath, conf, iou, labels, threads)
                    }
                }
            } catch (e: TimeoutCancellationException) {
                throw McpToolException(McpErrorCode.TIMEOUT, "检测超时", "换小模型或降低输入尺寸后重试")
            } catch (e: McpToolException) {
                throw e
            } catch (e: IllegalArgumentException) {
                throw McpToolException(McpErrorCode.INVALID_PARAMS, e.message ?: "参数错误", null)
            } catch (e: Exception) {
                throw McpToolException(McpErrorCode.INTERNAL, "检测失败：${e.localizedMessage}", "确认模型为 Ultralytics 导出的 fp32 TFLite")
            }
            return McpToolResult.json(JsonObject().apply {
                addProperty("count", detections.size)
                addProperty("imageWidth", source.width)
                addProperty("imageHeight", source.height)
                add("detections", JsonArray().apply {
                    detections.forEach { d ->
                        add(JsonObject().apply {
                            addProperty("label", d.label)
                            addProperty("confidence", d.confidence)
                            add("bounds", JsonObject().apply {
                                addProperty("left", d.bounds.left)
                                addProperty("top", d.bounds.top)
                                addProperty("right", d.bounds.right)
                                addProperty("bottom", d.bounds.bottom)
                                addProperty("centerX", d.bounds.centerX())
                                addProperty("centerY", d.bounds.centerY())
                            })
                        })
                    }
                })
            })
        } finally {
            runCatching { source.recycle() }
        }
    }
}
