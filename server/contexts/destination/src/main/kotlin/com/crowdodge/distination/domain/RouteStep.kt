package com.crowdodge.distination.domain

data class RouteStep (
    val fromName: String,
    val toName: String,
    val lineName: String,
    val moveType: String, // HACK: ビジネスロジックとして、enum classにすべき可能性がある。
    val durationMin: Int,
    val distanceMeter: Int?,
    val callingAt: List<String> = emptyList(),
)