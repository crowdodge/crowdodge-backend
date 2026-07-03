package com.crowdodge.event.infrastructure.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.port.CalendarSyncBatch
import com.crowdodge.event.application.port.CalendarSyncFetchResult
import com.crowdodge.event.application.port.GoogleCalendarEventsGateway
import com.crowdodge.event.application.port.IncomingCalendarEvent
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.EventContent
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.GoogleEventId.Companion.googleEventId
import com.crowdodge.event.domain.model.RecurringEventId.Companion.recurringEventId
import com.crowdodge.event.domain.model.RemindTiming.Companion.remindTiming
import com.crowdodge.event.domain.model.Schedule.Companion.schedule
import com.crowdodge.shared.kernel.AppTime
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.LocalDate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

@Suppress("TooManyFunctions")
class GoogleCalendarGateway(
    private val config: GoogleCalendarConfig,
    private val httpClient: HttpClient,
) : GoogleCalendarEventsGateway {
    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    override suspend fun incrementalSync(
        connection: CalendarConnection,
        syncToken: String,
    ): Either<EventError.ExternalError, CalendarSyncFetchResult> =
        fetchEvents(connection, EventsListRequest.Incremental(syncToken))

    override suspend fun fullSync(
        connection: CalendarConnection,
        windowStart: Instant,
        windowEnd: Instant,
    ): Either<EventError.ExternalError, CalendarSyncBatch> =
        fetchEvents(connection, EventsListRequest.Full(windowStart, windowEnd)).fold(
            { it.left() },
            { result ->
                when (result) {
                    is CalendarSyncFetchResult.Events -> result.batch.right()
                    CalendarSyncFetchResult.SyncTokenExpired -> googleError()
                }
            },
        )

    @Suppress("NestedBlockDepth", "ReturnCount")
    private suspend fun fetchEvents(
        connection: CalendarConnection,
        request: EventsListRequest,
    ): Either<EventError.ExternalError, CalendarSyncFetchResult> {
        val calendarId = connection.calendarId
        val accessToken = connection.accessToken
        val events = mutableListOf<IncomingCalendarEvent>()
        val cancellations = mutableListOf<GoogleEventId>()
        var pageToken: String? = null
        var nextSyncToken: String? = null

        do {
            val response = send {
                httpClient.get(eventsListUrl(calendarId, request, pageToken)) {
                    bearerAuth(accessToken)
                }
            }.fold({ return it.left() }, { it })

            if (response.status == HttpStatusCode.Gone && request is EventsListRequest.Incremental) {
                return CalendarSyncFetchResult.SyncTokenExpired.right()
            }
            if (!response.status.isSuccess()) return googleError()

            val page = runCatchingPreservingCancellation {
                json.decodeFromString<EventsListResponse>(response.bodyAsText())
            }.getOrNull()
                ?: return googleError()
            page.items.forEach { event ->
                if (event.status == STATUS_CANCELLED) {
                    val googleEventId = runCatching { arrow.core.raise.either { googleEventId(event.id) } }.getOrNull()
                        ?.getOrNull()
                    if (googleEventId != null) cancellations += googleEventId
                } else {
                    events += event.toIncomingEvent() ?: return googleError()
                }
            }
            pageToken = page.nextPageToken
            nextSyncToken = page.nextSyncToken ?: nextSyncToken
        } while (pageToken != null)

        return CalendarSyncFetchResult.Events(
            CalendarSyncBatch(
                upserts = events,
                cancellations = cancellations,
                nextSyncToken = nextSyncToken,
            ),
        ).right()
    }

    private fun eventsListUrl(calendarId: String, request: EventsListRequest, pageToken: String?): String {
        val params = linkedMapOf(
            "singleEvents" to "true",
            "showDeleted" to "true",
            "maxResults" to MAX_RESULTS.toString(),
        )
        when (request) {
            is EventsListRequest.Full -> {
                params["timeMin"] = request.windowStart.toString()
                params["timeMax"] = request.windowEnd.toString()
            }
            is EventsListRequest.Incremental -> {
                params["syncToken"] = request.syncToken
            }
        }
        if (pageToken != null) params["pageToken"] = pageToken
        return apiUrl("/calendar/v3/calendars/${calendarId.pathSegment()}/events", params)
    }

    private fun GoogleCalendarEvent.toIncomingEvent(): IncomingCalendarEvent? =
        runCatching {
            val eventSchedule = schedule()
            arrow.core.raise.either {
                IncomingCalendarEvent(
                    googleEventId = googleEventId(id),
                    recurringEventId = recurringEventId?.let { recurringEventId(it) },
                    originalStart = originalStartTime?.toInstant(),
                    eventContent = EventContent(
                        title = summary,
                        description = description,
                        location = location,
                        schedule = eventSchedule,
                        remindTiming = reminderMinutes()?.let { remindTiming(it.minutes) },
                    ),
                )
            }.getOrNull()
        }.getOrNull()

    private fun GoogleCalendarEvent.schedule(): com.crowdodge.event.domain.model.Schedule =
        arrow.core.raise.either {
            val start = requireNotNull(start)
            val end = requireNotNull(end)
            when {
                start.dateTime != null && end.dateTime != null -> schedule(
                    startTime = Instant.parse(start.dateTime),
                    endTime = Instant.parse(end.dateTime),
                )
                start.date != null && end.date != null -> schedule(
                    startDate = LocalDate.parse(start.date),
                    endDate = LocalDate.parse(end.date),
                )
                else -> error("unsupported Google Calendar event time shape")
            }
        }.getOrNull() ?: error("invalid Google Calendar event schedule")

    private fun GoogleEventDateTime.toInstant(): Instant? =
        dateTime?.let(Instant::parse) ?: date?.let { AppTime.startOfBusinessDate(LocalDate.parse(it)) }

    private fun GoogleCalendarEvent.reminderMinutes(): Int? =
        reminders
            ?.overrides
            ?.firstOrNull { it.minutes > 0 }
            ?.minutes

    private fun apiUrl(path: String, params: Map<String, String> = emptyMap()): String {
        val query = params.entries.joinToString("&") { (key, value) -> "${key.query()}=${value.query()}" }
        val suffix = if (query.isBlank()) "" else "?$query"
        return "${config.apiBaseUrl}$path$suffix"
    }

    private suspend fun send(
        block: suspend () -> HttpResponse,
    ): Either<EventError.ExternalError, HttpResponse> =
        try {
            block().right()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: SocketTimeoutException) {
            EventError.ExternalError.GoogleCalendarTimeoutError.left()
        } catch (_: ConnectTimeoutException) {
            EventError.ExternalError.GoogleCalendarTimeoutError.left()
        } catch (_: HttpRequestTimeoutException) {
            EventError.ExternalError.GoogleCalendarTimeoutError.left()
        } catch (_: Throwable) {
            EventError.ExternalError.GoogleCalendarError.left()
        }

    @Suppress("TooGenericExceptionCaught")
    private inline fun <T> runCatchingPreservingCancellation(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }

    private fun <T> googleError(): Either<EventError.ExternalError, T> =
        EventError.ExternalError.GoogleCalendarError.left()

    private fun String.pathSegment(): String = encode().replace("%2F", "%2F")

    private fun String.query(): String = encode()

    private fun String.encode(): String =
        URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

    private companion object {
        private const val STATUS_CANCELLED = "cancelled"
        private const val MAX_RESULTS = 2500
    }
}

private sealed interface EventsListRequest {
    data class Full(
        val windowStart: Instant,
        val windowEnd: Instant,
    ) : EventsListRequest

    data class Incremental(
        val syncToken: String,
    ) : EventsListRequest
}

data class GoogleCalendarConfig(
    val apiBaseUrl: String,
    val fullSyncWindowDays: Int,
)

@Serializable
private data class EventsListResponse(
    val items: List<GoogleCalendarEvent> = emptyList(),
    val nextPageToken: String? = null,
    val nextSyncToken: String? = null,
)

@Serializable
private data class GoogleCalendarEvent(
    val id: String,
    val status: String? = null,
    val summary: String? = null,
    val description: String? = null,
    val location: String? = null,
    val start: GoogleEventDateTime? = null,
    val end: GoogleEventDateTime? = null,
    val reminders: GoogleEventReminders? = null,
    val recurringEventId: String? = null,
    val originalStartTime: GoogleEventDateTime? = null,
)

@Serializable
private data class GoogleEventDateTime(
    val date: String? = null,
    val dateTime: String? = null,
)

@Serializable
private data class GoogleEventReminders(
    val overrides: List<GoogleEventReminder> = emptyList(),
)

@Serializable
private data class GoogleEventReminder(
    val minutes: Int,
)
