package com.crowdodge.congestion.infrastructure.hash

import com.crowdodge.congestion.application.port.CongestionGenerationSource
import com.crowdodge.congestion.application.port.GenerationInputHashCalculator
import com.crowdodge.congestion.infrastructure.serialization.CongestionGenerationJsonEncoder
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.security.MessageDigest
import java.util.HexFormat

class Sha256GenerationInputHashCalculator : GenerationInputHashCalculator {
    override fun calculate(source: CongestionGenerationSource): String {
        val input = GenerationInputV1(
            version = HASH_VERSION,
            start = source.start.toString(),
            end = source.end.toString(),
            isAllDay = source.isAllDay,
            destination = CongestionGenerationJsonEncoder.destination(source.destination),
            outboundRoute = CongestionGenerationJsonEncoder.route(source.outboundRoute),
            travelDurationMillis = source.travelDuration.inWholeMilliseconds,
        )
        val serialized = Json.encodeToString(input)
        val digest = MessageDigest.getInstance("SHA-256").digest(serialized.encodeToByteArray())
        return HexFormat.of().formatHex(digest)
    }

    private companion object {
        const val HASH_VERSION = 1
    }
}

@Serializable
private data class GenerationInputV1(
    val version: Int,
    val start: String,
    val end: String,
    val isAllDay: Boolean,
    val destination: JsonObject,
    val outboundRoute: JsonObject,
    val travelDurationMillis: Long,
)
