package com.crowdodge.distination.domain.aggregates.eventdestination

import com.crowdodge.shared.kernel.Location

data class Destination(
    val address: String,
    val location: Location,
)
