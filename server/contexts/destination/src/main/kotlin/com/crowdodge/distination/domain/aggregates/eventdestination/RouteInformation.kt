package com.crowdodge.distination.domain.aggregates.eventdestination

data class RouteInformation(
    val steps: List<RouteStep>,
)