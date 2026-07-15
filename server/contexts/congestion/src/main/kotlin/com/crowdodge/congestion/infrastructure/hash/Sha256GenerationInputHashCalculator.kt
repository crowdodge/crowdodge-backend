package com.crowdodge.congestion.infrastructure.hash

import com.crowdodge.congestion.application.port.CongestionGenerationSource
import com.crowdodge.congestion.application.port.GenerationInputHashCalculator
import com.crowdodge.congestion.infrastructure.serialization.CongestionGenerationJsonEncoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** 混雑生成入力の正規化 JSON から SHA-256 ハッシュを計算する。 */
class Sha256GenerationInputHashCalculator : GenerationInputHashCalculator {
    override fun calculate(source: CongestionGenerationSource): String {
        val canonical = canonicalObject(
            mapOf(
                "start" to JsonPrimitive(source.start.toString()),
                "end" to JsonPrimitive(source.end.toString()),
                "isAllDay" to JsonPrimitive(source.isAllDay),
                "destination" to CongestionGenerationJsonEncoder.destination(source.destination),
                "outboundRoute" to CongestionGenerationJsonEncoder.route(source.outboundRoute),
                "travelDurationMillis" to JsonPrimitive(source.travelDuration.inWholeMilliseconds),
            ),
        )
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString(separator = "") { "%02x".format(it) }
    }

    private fun canonicalObject(values: Map<String, JsonElement>): String =
        values.entries
            .sortedWith { left, right -> compareUnicodeCodePoints(left.key, right.key) }
            .joinToString(prefix = "{", postfix = "}", separator = ",") { (key, value) ->
                "${JsonPrimitive(key)}:${canonical(value)}"
            }

    private fun canonical(element: JsonElement): String = when (element) {
        JsonNull -> "null"
        is JsonObject -> canonicalObject(element)
        is kotlinx.serialization.json.JsonArray ->
            element.joinToString(prefix = "[", postfix = "]", transform = ::canonical)
        is JsonPrimitive -> canonicalPrimitive(element)
    }

    private fun canonicalPrimitive(value: JsonPrimitive): String = when {
        value.isString -> JsonPrimitive(value.content).toString()
        value.content == "true" || value.content == "false" -> value.content
        else -> BigDecimal(value.content).stripTrailingZeros().toPlainString()
    }

    private fun compareUnicodeCodePoints(left: String, right: String): Int {
        val leftPoints = left.codePoints().toArray()
        val rightPoints = right.codePoints().toArray()
        val commonSize = minOf(leftPoints.size, rightPoints.size)
        for (index in 0 until commonSize) {
            val comparison = leftPoints[index].compareTo(rightPoints[index])
            if (comparison != 0) return comparison
        }
        return leftPoints.size.compareTo(rightPoints.size)
    }
}
