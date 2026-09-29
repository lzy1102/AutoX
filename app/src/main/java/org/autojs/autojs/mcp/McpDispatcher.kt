package org.autojs.autojs.mcp

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import org.autojs.autojs.mcp.tools.McpErrorCode
import org.autojs.autojs.mcp.tools.McpToolException
import org.autojs.autojs.mcp.tools.McpToolResult

/**
 * 分发结果。
 *
 * 注意 [Reply] 的状态码恒为 200，但 body 可能是 JSON-RPC 错误信封——
 * 按规范，`-32602`（参数错误）这类属于「请求被正常处理但语义失败」，不是 HTTP 层错误。
 */
sealed class McpDispatchResult {

    /** 200 + JSON body（body 可能是成功响应，也可能是 JSON-RPC 错误响应） */
    data class Reply(val body: JsonObject) : McpDispatchResult()

    /** 通知已被接受：202 + 空 body */
    object Accepted : McpDispatchResult()

    /** 需要非 200 状态码的 JSON-RPC 错误（版本不支持 400 / 未知方法 404） */
    data class Failed(val status: HttpStatusCode, val body: JsonObject) : McpDispatchResult()
}

/**
 * `method` → handler 分发。
 *
 * 兼容策略：旧握手方法在这里只是「无状态分支」，不创建会话、不读写任何会话状态，
 * 因此同一时间新旧客户端混连互不干扰。
 */
class McpDispatcher(private val registry: McpRegistry) {

    suspend fun dispatch(root: JsonObject, context: McpCallContext): McpDispatchResult {
        val id = McpJsonRpc.idOf(root)
        val method = McpJsonRpc.methodOf(root)
            ?: return McpDispatchResult.Reply(
                McpJsonRpc.error(id, McpProtocol.ERROR_INVALID_REQUEST, "Missing or invalid 'method'")
            )

        return when (method) {
            McpProtocol.METHOD_DISCOVER -> reply(id, McpProtocol.discoverResult())

            McpProtocol.METHOD_TOOLS_LIST -> reply(id, registry.toolsListResult())

            McpProtocol.METHOD_TOOLS_CALL -> callTool(id, root, context)

            // ---- 旧握手兼容分支（均为纯无状态处理）----
            McpProtocol.METHOD_INITIALIZE -> reply(id, initializeResult(root))
            McpProtocol.METHOD_PING -> reply(id, JsonObject())
            McpProtocol.METHOD_LOGGING_SET_LEVEL -> reply(id, JsonObject())
            McpProtocol.METHOD_RESOURCES_LIST -> reply(id, emptyListResult("resources"))
            McpProtocol.METHOD_PROMPTS_LIST -> reply(id, emptyListResult("prompts"))

            else -> McpDispatchResult.Failed(
                HttpStatusCode.NotFound,
                McpJsonRpc.error(id, McpProtocol.ERROR_METHOD_NOT_FOUND, "Method not found: $method")
            )
        }
    }

    private fun reply(id: JsonElement?, result: JsonObject): McpDispatchResult =
        McpDispatchResult.Reply(McpJsonRpc.success(id, result))

    /**
     * 旧 `initialize` 的响应。
     *
     * 版本：客户端请求的是已知版本就原样回显（最不容易让旧客户端断开），否则回落到旧版基线。
     * capabilities 不回 `logging` —— 该能力已在 2026-07-28 中移除。
     */
    private fun initializeResult(root: JsonObject): JsonObject {
        val requested = McpJsonRpc.paramsOf(root)
            ?.get("protocolVersion")
            ?.takeIf { it.isJsonPrimitive }?.asString
        return JsonObject().apply {
            addProperty("protocolVersion", McpProtocol.negotiateLegacyVersion(requested))
            add("capabilities", McpProtocol.capabilities())
            add("serverInfo", McpProtocol.serverInfo())
            addProperty("instructions", McpProtocol.instructions)
        }
    }

    /** 旧的列表方法：很多客户端启动时会探测，直接回空数组比回 -32601 体验更好 */
    private fun emptyListResult(key: String): JsonObject = JsonObject().apply {
        addProperty(McpProtocol.RESULT_TYPE, McpProtocol.RESULT_TYPE_COMPLETE)
        add(key, JsonArray())
        addProperty(McpProtocol.TTL_MS, McpConfig.TOOLS_TTL_MS)
        addProperty(McpProtocol.CACHE_SCOPE, McpProtocol.CACHE_SCOPE_PUBLIC)
    }

    private suspend fun callTool(
        id: JsonElement?,
        root: JsonObject,
        context: McpCallContext,
    ): McpDispatchResult {
        val params = McpJsonRpc.paramsOf(root)
            ?: return invalidParams(id, "tools/call 缺少 params")
        val name = params.get("name")?.takeIf { it.isJsonPrimitive }?.asString
            ?: return invalidParams(id, "tools/call 缺少 params.name")

        val tool = registry.get(name)
            ?: return McpDispatchResult.Reply(
                McpJsonRpc.error(
                    id,
                    McpProtocol.ERROR_INVALID_PARAMS,
                    "未知工具: $name",
                    JsonObject().apply {
                        addProperty("type", "UnknownTool")
                        add("availableTools", JsonArray().apply { registry.names().forEach { add(it) } })
                    },
                )
            )

        val arguments = params.get("arguments")
            ?.takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()

        val result = try {
            tool.call(arguments, context)
        } catch (e: McpToolException) {
            McpToolResult.error(e.code, e.message ?: "工具执行失败", e.hint)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "tool $name failed", e)
            McpToolResult.error(
                McpErrorCode.INTERNAL,
                e.localizedMessage ?: e.javaClass.simpleName,
            )
        }
        return McpDispatchResult.Reply(McpJsonRpc.success(id, result.toJson()))
    }

    private fun invalidParams(id: JsonElement?, message: String): McpDispatchResult =
        McpDispatchResult.Reply(
            McpJsonRpc.error(id, McpProtocol.ERROR_INVALID_PARAMS, message)
        )

    private companion object {
        const val TAG = "McpDispatcher"
    }
}
