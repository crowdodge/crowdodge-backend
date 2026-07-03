package com.crowdodge.user.application.port

import com.crowdodge.user.domain.model.GrantedGoogleScopes

val REQUIRED_GOOGLE_CALENDAR_SCOPES: Set<String> = setOf(
    "https://www.googleapis.com/auth/calendar.events",
    "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
)

fun GrantedGoogleScopes.hasRequiredGoogleCalendarScopes(): Boolean =
    value.split(Regex("\\s+")).toSet().containsAll(REQUIRED_GOOGLE_CALENDAR_SCOPES)
