package com.crowdodge.distination.infrastructure

import kotlinx.serialization.Serializable

/** 目的地までの経路情報。 */
@Serializable
data class RouteInformation(
    val routeSteps: List<RouteStep>,
)

/** 経路を構成する一区間。 */
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
