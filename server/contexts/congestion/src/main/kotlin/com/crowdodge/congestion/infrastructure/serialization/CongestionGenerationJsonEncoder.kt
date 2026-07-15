package com.crowdodge.congestion.infrastructure.serialization

import com.crowdodge.congestion.application.port.CongestionDestination
import com.crowdodge.congestion.application.port.CongestionRoute
import com.crowdodge.congestion.application.port.CongestionRouteStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 混雑生成元の構成要素を安定した JSON 表現へ変換する。 */
internal object CongestionGenerationJsonEncoder {
    fun destination(destination: CongestionDestination): JsonObject = buildJsonObject {
        put("destination", destination.name)
        put("latitude", destination.latitude)
        put("longitude", destination.longitude)
    }

    fun route(route: CongestionRoute): JsonObject = buildJsonObject {
        put("routeSteps", JsonArray(route.steps.map(::routeStep)))
    }

    private fun routeStep(step: CongestionRouteStep): JsonObject = buildJsonObject {
        put("fromName", step.fromName)
        put("toName", step.toName)
        put("lineName", step.lineName)
        put("moveType", step.moveType)
        put("callingAt", JsonArray(step.callingAt.map(::JsonPrimitive)))
    }
}
