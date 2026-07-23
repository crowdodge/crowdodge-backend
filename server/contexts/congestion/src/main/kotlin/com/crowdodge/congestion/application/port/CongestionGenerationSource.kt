package com.crowdodge.congestion.application.port

import com.crowdodge.congestion.domain.model.EventUuid
import kotlin.time.Duration
import kotlin.time.Instant

/** 混雑予測の生成に必要な予定・目的地・経路。 */
data class CongestionGenerationSource(
    val eventUuid: EventUuid,
    val start: Instant,
    val end: Instant,
    val isAllDay: Boolean,
    val destination: CongestionDestination,
    val outboundRoute: CongestionRoute,
    val travelDuration: Duration,
)

/** 混雑調査の対象となる目的地。 */
data class CongestionDestination(
    val name: String,
    val latitude: Double,
    val longitude: Double,
)

/** 目的地までの経路。 */
data class CongestionRoute(
    val steps: List<CongestionRouteStep>,
)

/** 経路を構成する一区間。 */
data class CongestionRouteStep(
    val fromName: String,
    val toName: String,
    val lineName: String,
    val moveType: String,
    val callingAt: List<String>,
)
