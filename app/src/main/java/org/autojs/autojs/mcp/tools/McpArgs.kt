package org.autojs.autojs.mcp.tools

import com.google.gson.JsonObject

/** arguments 解析小工具：缺参/类型错误统一转成带内参数错误，由上层转 isError. */
internal fun JsonObject.optString(key: String, default: String? = null): String? {
    val e = get(key) ?: return default
    if (!e.isJsonPrimitive) return default
    val p = e.asJsonPrimitive
    return when {
        p.isString -> p.asString
        p.isNumber -> p.asString
        p.isBoolean -> p.asString
        else -> default
    }
}

internal fun JsonObject.reqString(key: String): String {
    val v = optString(key, null)?.trim()
    if (v.isNullOrEmpty()) {
        throw McpToolException(McpErrorCode.INVALID_PARAMS, "缺少参数: $key", "请提供 $key 后重试")
    }
    return v
}

internal fun JsonObject.optInt(key: String, default: Int): Int {
    val e = get(key) ?: return default
    if (!e.isJsonPrimitive) return default
    val p = e.asJsonPrimitive
    return try {
        when {
            p.isNumber -> p.asInt
            p.isString -> p.asString.trim().toInt()
            p.isBoolean -> if (p.asBoolean) 1 else 0
            else -> default
        }
    } catch (_: Exception) {
        default
    }
}

internal fun JsonObject.optLong(key: String, default: Long): Long {
    val e = get(key) ?: return default
    if (!e.isJsonPrimitive) return default
    val p = e.asJsonPrimitive
    return try {
        when {
            p.isNumber -> p.asLong
            p.isString -> p.asString.trim().toLong()
            p.isBoolean -> if (p.asBoolean) 1L else 0L
            else -> default
        }
    } catch (_: Exception) {
        default
    }
}

internal fun JsonObject.optBoolean(key: String, default: Boolean): Boolean {
    val e = get(key) ?: return default
    if (!e.isJsonPrimitive) return default
    val p = e.asJsonPrimitive
    return try {
        when {
            p.isBoolean -> p.asBoolean
            p.isNumber -> p.asInt != 0
            p.isString -> when (p.asString.trim().lowercase()) {
                "true", "1", "yes", "y" -> true
                "false", "0", "no", "n" -> false
                else -> default
            }
            else -> default
        }
    } catch (_: Exception) {
        default
    }
}

internal fun JsonObject.optDouble(key: String, default: Double): Double {
    val e = get(key) ?: return default
    if (!e.isJsonPrimitive) return default
    val p = e.asJsonPrimitive
    return try {
        when {
            p.isNumber -> p.asDouble
            p.isString -> p.asString.trim().toDouble()
            else -> default
        }
    } catch (_: Exception) {
        default
    }
}
