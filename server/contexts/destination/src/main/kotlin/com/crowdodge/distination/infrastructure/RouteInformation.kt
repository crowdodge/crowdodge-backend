package com.crowdodge.distination.infrastructure

import kotlinx.serialization.Serializable

@Serializable
data class RouteInformation(
    val routeSteps: List<RouteStep>,
)

@Serializable
data class RouteStep(
    val fromName: String,
    val toName: String,
    val lineName: String,
    val moveType: String,
    val durationMin: Int,
    val distanceMeter: Int?,
    val callingAt: List<String> = emptyList(),
)
