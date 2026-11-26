package cz.prazicka.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** Pomocné funkce pro čtení zpráv podle API (neznámá pole se ignorují, null zůstává null). */

val ProtoJson = Json { ignoreUnknownKeys = true; isLenient = false }

fun parseObject(line: String): JsonObject? = try {
    ProtoJson.parseToJsonElement(line).jsonObject
} catch (e: Exception) {
    null
}

private fun JsonElement?.prim(): JsonPrimitive? = this as? JsonPrimitive

fun JsonObject.str(key: String): String? {
    val p = this[key].prim() ?: return null
    if (p is JsonNull || !p.isString) return null
    return p.content
}

fun JsonObject.dbl(key: String): Double? {
    val p = this[key].prim() ?: return null
    if (p is JsonNull || p.isString) return null
    return p.doubleOrNull
}

fun JsonObject.lng(key: String): Long? {
    val p = this[key].prim() ?: return null
    if (p is JsonNull || p.isString) return null
    return p.longOrNull ?: p.doubleOrNull?.toLong()
}

fun JsonObject.int(key: String): Int? = lng(key)?.toInt()

fun JsonObject.bool(key: String): Boolean? {
    val p = this[key].prim() ?: return null
    if (p is JsonNull || p.isString) return null
    return p.booleanOrNull
}

fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.arr(key: String): JsonArray? = this[key] as? JsonArray

fun JsonElement?.asDouble(): Double? {
    val p = this.prim() ?: return null
    if (p is JsonNull || p.isString) return null
    return p.doubleOrNull
}

fun JsonElement?.asBool(): Boolean? {
    val p = this.prim() ?: return null
    if (p is JsonNull || p.isString) return null
    return p.booleanOrNull
}

fun JsonElement?.asString(): String? {
    val p = this.prim() ?: return null
    if (p is JsonNull || !p.isString) return null
    return p.content
}

/** Zaokrouhlení na 1 desetinné místo pro odesílání (API: teploty a procenta). */
fun r1(v: Double): Double = Math.round(v * 10.0) / 10.0
