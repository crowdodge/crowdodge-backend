package com.crowdodge.distination.domain

@JvmInline
value class DestinationHint(val value: String) {
    fun isProvided(): Boolean = value.isNotBlank()
}