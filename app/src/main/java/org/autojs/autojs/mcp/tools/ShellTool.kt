package org.autojs.autojs.mcp.tools

import com.google.gson.JsonObject
import com.stardust.autojs.core.util.ProcessShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.autojs.autojs.mcp.McpCallContext
import org.autojs.autojs.mcp.McpConfig

/** shell_exec：同步执行 shell 并回 stdout/stderr. */
internal class ShellTool : McpTool {
    override val name = "shell_exec"
    override val description = """
        在设备上同步执行 shell 命令，返回 exitCode/stdout/stderr。
        root=true 时走 su，需要设备已 root。耗时操作请设小 timeoutMs（默认 ${McpConfig.SHELL_TIMEOUT_MS}ms）。
    """.trimIndent()
    override val inputSchema: JsonObject = schemaOf(
        mapOf(
            "command" to stringProp("要执行的 shell 命令，可多行"),
            "root" to boolProp("是否用 root 执行", false),
            "timeoutMs" to intProp("超时毫秒，上限 60000", null, 1000, 60000),
        ),
        required = listOf("command"),
    )

    override suspend fun call(arguments: JsonObject, context: McpCallContext): McpToolResult {
        val command = arguments.reqString("command")
        val root = arguments.optBoolean("root", false)
        val timeoutMs = arguments.optLong("timeoutMs", McpConfig.SHELL_TIMEOUT_MS)
            .coerceIn(1000L, 60_000L)
        if (command.length > 8000) {
            throw McpToolException(McpErrorCode.INVALID_PARAMS, "命令过长（${command.length} 字符，上限 8000）", "请拆成多次调用")
        }
        val result = try {
            withTimeout(timeoutMs) {
                withContext(Dispatchers.IO) { ProcessShell.execCommand(command, root) }
            }
        } catch (e: TimeoutCancellationException) {
            throw McpToolException(McpErrorCode.TIMEOUT, "shell 执行超时（${timeoutMs}ms）", "请缩小命令范围或调大 timeoutMs")
        } catch (e: McpToolException) {
            throw e
        } catch (e: Exception) {
            throw McpToolException(McpErrorCode.INTERNAL, "shell 执行失败：${e.localizedMessage}", null)
        }
        val structured = JsonObject().apply {
            addProperty("exitCode", result.code)
            addProperty("stdout", result.result ?: "")
            addProperty("stderr", result.error ?: "")
        }
        return McpToolResult.json(structured)
    }
}
