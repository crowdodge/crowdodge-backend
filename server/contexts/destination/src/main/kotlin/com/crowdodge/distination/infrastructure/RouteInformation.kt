package com.crowdodge.distination.infrastructure

import kotlinx.serialization.Serializable

@Serializable
data class RouteInformation(
    val routeSteps: List<RouteStep>
)

@Serializable
data class RouteStep(
    val fromName: String, // 出発地点名（駅名や「出発地」など）
    val toName: String, // 到着地点名（駅名や「目的地」など）
    val lineName: String, // 路線名。徒歩の場合は「徒歩」が入る
    val moveType: String, // "walk"（徒歩）か "local_train"（電車）などの種別
    val durationMin: Int, // 移動時間（分）
    val distanceMeter: Int? // 移動距離（メートル）
)
