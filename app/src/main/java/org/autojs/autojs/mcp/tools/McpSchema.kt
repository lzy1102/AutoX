package org.autojs.autojs.mcp.tools

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/** JSON Schema 构造小工具，减少各工具的样板代码. */
internal fun stringProp(description: String, default: String? = null): JsonObject =
    JsonObject().apply {
        addProperty("type", "string")
        addProperty("description", description)
        default?.let { addProperty("default", it) }
    }

internal fun intProp(description: String, default: Int? = null, minimum: Int? = null, maximum: Int? = null): JsonObject =
    JsonObject().apply {
        addProperty("type", "integer")
        addProperty("description", description)
        default?.let { addProperty("default", it) }
        minimum?.let { addProperty("minimum", it) }
        maximum?.let { addProperty("maximum", it) }
    }

internal fun boolProp(description: String, default: Boolean? = null): JsonObject =
    JsonObject().apply {
        addProperty("type", "boolean")
        addProperty("description", description)
        default?.let { addProperty("default", it) }
    }

internal fun schemaOf(properties: Map<String, JsonObject>, required: List<String> = emptyList()): JsonObject =
    JsonObject().apply {
        addProperty("type", "object")
        add("properties", JsonObject().apply {
            properties.forEach { (k, v) -> add(k, v) }
        })
        if (required.isNotEmpty()) {
            add("required", JsonArray().apply { required.forEach { add(it) } })
        }
        addProperty("additionalProperties", false)
    }
