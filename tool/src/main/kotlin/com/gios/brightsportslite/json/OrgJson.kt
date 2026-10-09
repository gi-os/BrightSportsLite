package com.gios.brightsportslite.json

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Just enough of `org.json` to run the full app's parsers unchanged.
 *
 * BrightSports reads every provider through Android's bundled `org.json`. That package is
 * part of the Android platform rather than a library, so the SDK's dependency allowlist
 * does not cover a copy of it for unit tests, and the parsers are the part most worth
 * testing. These two classes sit on top of kotlinx.serialization's tree and reproduce the
 * lenient `opt*` behaviour the parsers rely on: a missing key is never an error, numbers
 * and strings convert into each other, and a JSON null reads as absent.
 */
class JSONObject private constructor(private var fields: Map<String, JsonElement>) {

    constructor() : this(emptyMap())

    /** Parses [source]; throws on anything that is not a JSON object, like the original. */
    constructor(source: String) : this(parseObject(source))

    internal constructor(element: JsonObject) : this(element as Map<String, JsonElement>)

    fun has(key: String): Boolean = fields.containsKey(key)

    fun isNull(key: String): Boolean = fields[key].let { it == null || it is JsonNull }

    fun keys(): Iterator<String> = fields.keys.iterator()

    fun length(): Int = fields.size

    fun optJSONObject(key: String): JSONObject? = (fields[key] as? JsonObject)?.let { JSONObject(it) }

    fun optJSONArray(key: String): JSONArray? = (fields[key] as? JsonArray)?.let { JSONArray(it) }

    fun optString(key: String): String = optString(key, "")

    fun optString(key: String, fallback: String): String = asString(fields[key]) ?: fallback

    fun optInt(key: String): Int = optInt(key, 0)

    fun optInt(key: String, fallback: Int): Int = asDouble(fields[key])?.toInt() ?: fallback

    fun optLong(key: String, fallback: Long = 0L): Long = asDouble(fields[key])?.toLong() ?: fallback

    fun optDouble(key: String): Double = optDouble(key, Double.NaN)

    fun optDouble(key: String, fallback: Double): Double = asDouble(fields[key]) ?: fallback

    fun optBoolean(key: String): Boolean = optBoolean(key, false)

    fun optBoolean(key: String, fallback: Boolean): Boolean = asBoolean(fields[key]) ?: fallback

    fun getString(key: String): String = optString(key, missing(key))

    fun getJSONArray(key: String): JSONArray = optJSONArray(key) ?: missing(key)

    fun getJSONObject(key: String): JSONObject = optJSONObject(key) ?: missing(key)

    fun put(key: String, value: Any?): JSONObject {
        fields = fields + (key to toElement(value))
        return this
    }

    internal fun element(): JsonObject = JsonObject(fields)

    override fun toString(): String = element().toString()
}

class JSONArray private constructor(private var items: List<JsonElement>) {

    constructor() : this(emptyList())

    constructor(source: String) : this(parseArray(source))

    internal constructor(element: JsonArray) : this(element as List<JsonElement>)

    fun length(): Int = items.size

    fun optJSONObject(index: Int): JSONObject? = (items.getOrNull(index) as? JsonObject)?.let { JSONObject(it) }

    fun optJSONArray(index: Int): JSONArray? = (items.getOrNull(index) as? JsonArray)?.let { JSONArray(it) }

    fun optString(index: Int): String = asString(items.getOrNull(index)) ?: ""

    fun optInt(index: Int, fallback: Int = 0): Int = asDouble(items.getOrNull(index))?.toInt() ?: fallback

    fun getJSONObject(index: Int): JSONObject = optJSONObject(index) ?: missing("[$index]")

    fun put(value: Any?): JSONArray {
        items = items + toElement(value)
        return this
    }

    internal fun element(): JsonArray = JsonArray(items)

    override fun toString(): String = element().toString()
}

private fun missing(what: String): Nothing = throw NoSuchElementException("No value for $what")

private val lenient = Json { isLenient = true }

private fun parseObject(source: String): JsonObject =
    lenient.parseToJsonElement(source) as? JsonObject
        ?: throw IllegalArgumentException("Not a JSON object")

private fun parseArray(source: String): JsonArray =
    lenient.parseToJsonElement(source) as? JsonArray
        ?: throw IllegalArgumentException("Not a JSON array")

private fun asString(e: JsonElement?): String? = when (e) {
    null, JsonNull -> null
    is JsonPrimitive -> e.content
    else -> e.toString()
}

private fun asDouble(e: JsonElement?): Double? {
    val p = e as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return p.content.trim().toDoubleOrNull()
}

private fun asBoolean(e: JsonElement?): Boolean? {
    val p = e as? JsonPrimitive ?: return null
    if (p is JsonNull) return null
    return when (p.content.lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }
}

private fun toElement(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is JSONObject -> value.element()
    is JSONArray -> value.element()
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
