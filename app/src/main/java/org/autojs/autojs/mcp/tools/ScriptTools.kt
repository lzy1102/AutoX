package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.stardust.autojs.execution.ExecutionConfig
import com.stardust.autojs.script.JavaScriptFileSource
import com.stardust.autojs.script.StringScriptSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.autojs.autojs.mcp.McpCallContext
import java.io.File

/** script_run：运行一段 JS 代码或脚本文件，立即返回 executionId. */
internal class ScriptRunTool : McpTool {
    override val name = "script_run"
    override val description = """
        运行 JavaScript：传 code（脚本内容）或 path（手机上的 .js 文件路径），立即返回 executionId，不等待结束。
        用 script_list 查运行状态，script_stop 停止。适合动态脚本；若只是想控制设备，优先用专用工具（input_*/ui_*/find_*）。
        UI 模式脚本（"ui";）会尝试弹出脚本界面，后台调用可能失败，建议用普通脚本。
    """.trimIndent()
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "code" to stringProp("JS 脚本内容（与 path 二选一）"),
            "path" to stringProp("脚本文件绝对路径（与 code 二选一），如 /sdcard/Scripts/a.js"),
            "name" to stringProp("展示用脚本名，默认 mcp_script"),
        ),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val code = arguments.optString("code", null)?.takeIf { it.isNotEmpty() }
            val path = arguments.optString("path", null)?.trim()?.takeIf { it.isNotEmpty() }
            val name = arguments.optString("name", null)?.trim()?.takeIf { it.isNotEmpty() }
            val source = when {
                code != null -> {
                    if (code.length > 200_000) {
                        throw McpToolException(McpErrorCode.INVALID_PARAMS, "code 过长（${code.length} 字符，上限 200000）", "请写成文件后用 path 运行")
                    }
                    StringScriptSource(name ?: "mcp_script", code)
                }
                path != null -> {
                    val file = File(path)
                    if (!file.isFile || !file.canRead()) {
                        throw McpToolException(McpErrorCode.INVALID_PARAMS, "脚本文件不存在或不可读：$path", "确认路径后重试")
                    }
                    JavaScriptFileSource(name ?: file.nameWithoutExtension, file)
                }
                else -> throw McpToolException(McpErrorCode.INVALID_PARAMS, "需传 code 或 path", null)
            }
            try {
                val execution = McpServices.autoJs().scriptEngineService
                    .execute(source, ExecutionConfig.default)
                McpToolResult.json(JsonObject().apply {
                    addProperty("executionId", execution.id)
                    addProperty("name", source.name)
                })
            } catch (e: McpToolException) {
                throw e
            } catch (e: Exception) {
                throw McpToolException(McpErrorCode.INTERNAL, "脚本启动失败：${e.localizedMessage}", null)
            }
        }
}

/** script_stop：停止指定脚本或全部脚本. */
internal class ScriptStopTool : McpTool {
    override val name = "script_stop"
    override val description = "停止脚本：传 executionId 停单个；不传则停止全部正在运行的脚本。"
    override val inputSchema: JsonObject = schemaOf(
        mapOf("executionId" to intProp("script_run 返回的 executionId，缺省停止全部")),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val service = McpServices.autoJs().scriptEngineService
            val id = arguments.optInt("executionId", -1)
            if (id >= 0) {
                val execution = service.getScriptExecution(id)
                    ?: throw McpToolException(McpErrorCode.INVALID_PARAMS, "找不到运行中的脚本：$id", "用 script_list 查询有效 id")
                val engine = execution.engine
                    ?: throw McpToolException(McpErrorCode.INTERNAL, "脚本 $id 引擎尚未就绪", "稍后重试")
                engine.forceStop()
                McpToolResult.json(JsonObject().apply {
                    addProperty("stopped", 1)
                    addProperty("executionId", id)
                })
            } else {
                val count = service.stopAll()
                McpToolResult.json(JsonObject().apply { addProperty("stopped", count) })
            }
        }
}

/** script_list：列出运行中的脚本. */
internal class ScriptListTool : McpTool {
    override val name = "script_list"
    override val description = "列出当前运行中的脚本（executionId、名称、来源摘要、引擎是否就绪）。"
    override val inputSchema: JsonObject = schemaOf(emptyMap())

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult =
        withContext(Dispatchers.IO) {
            val executions = McpServices.autoJs().scriptEngineService.scriptExecutions
            val arr = JsonArray()
            executions.forEach { execution ->
                arr.add(JsonObject().apply {
                    addProperty("executionId", execution.id)
                    addProperty("name", execution.source.name)
                    addProperty("source", execution.source.toString().take(200))
                    addProperty("engineReady", execution.engine != null)
                })
            }
            McpToolResult.json(JsonObject().apply {
                addProperty("count", arr.size())
                add("scripts", arr)
            })
        }
}
