package com.crowdodge.distination.domain.values

@JvmInline
value class DestinationHint(val value: String) {
    fun isProvided(): Boolean = value.isNotBlank()
}