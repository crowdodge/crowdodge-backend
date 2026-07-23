package com.crowdodge.user.application.port

enum class ProxyMethod { GET, POST, PATCH, DELETE }

val GOOGLE_CALENDAR_EVENT_QUERY_ALLOWLIST = setOf(
    "timeMin",
    "timeMax",
    "pageToken",
    "maxResults",
    "singleEvents",
    "orderBy",
    "showDeleted",
    "timeZone",
    "q",
    "eventTypes",
    "iCalUID",
    "privateExtendedProperty",
    "sharedExtendedProperty",
    "updatedMin",
    "syncToken",
)

data class CalendarProxyRequest(
    val method: ProxyMethod,
    val calendarId: String,
    val eventId: String?,
    val query: List<Pair<String, String>>,
    val contentType: String?,
    val body: ByteArray?,
)

data class CalendarProxyResponse(
    val status: Int,
    val contentType: String?,
    val body: ByteArray,
    val etag: String? = null,
)

fun interface GoogleCalendarProxyGateway {
    suspend fun proxy(
        request: CalendarProxyRequest,
        accessToken: String,
        refreshAccessToken: suspend () -> String?,
    ): CalendarProxyResponse
}
