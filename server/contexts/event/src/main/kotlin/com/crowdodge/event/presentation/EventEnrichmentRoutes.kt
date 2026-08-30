package com.crowdodge.event.presentation

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.event.application.port.CalendarEventEnrichments
import com.crowdodge.event.application.port.EventEnrichment
import com.crowdodge.event.application.port.EventEnrichmentCongestion
import com.crowdodge.event.application.port.EventEnrichmentDestination
import com.crowdodge.event.application.query.ListEventEnrichmentsError
import com.crowdodge.event.application.query.ListEventEnrichmentsUseCase
import com.crowdodge.shared.infra.web.Problem
import com.crowdodge.shared.infra.web.respondProblem
import com.crowdodge.shared.kernel.UserUuid
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable

fun Route.eventEnrichmentRoutes(
    useCase: ListEventEnrichmentsUseCase,
    resolveUserUuid: ApplicationCall.() -> UserUuid?,
) {
    get("/events") {
        val userUuid = call.resolveUserUuid() ?: return@get call.respondProblem(unauthorizedProblem())
        val rawCalendarIds = call.request.queryParameters.getAll("calendarId")
        val calendarIds = if (rawCalendarIds == null) {
            null
        } else {
            rawCalendarIds.toCalendarIds().fold(
                ifLeft = { return@get call.respondProblem(it.toProblem()) },
                ifRight = { it },
            )
        }

        useCase.handle(userUuid, calendarIds).fold(
            ifLeft = { call.respondProblem(it.toProblem()) },
            ifRight = { calendars -> call.respond(EventsResponse(calendars.map { it.toResponse() })) },
        )
    }
}

internal data object InvalidCalendarIdRequest

private fun List<String>.toCalendarIds(): Either<InvalidCalendarIdRequest, Set<String>> {
    val ids = flatMap { value -> value.split(',') }
    if (ids.any(String::isBlank) || ids.distinct().size != ids.size) return InvalidCalendarIdRequest.left()
    return ids.toSet().right()
}

private fun InvalidCalendarIdRequest.toProblem(): Problem = invalidCalendarIdProblem()

private fun ListEventEnrichmentsError.toProblem(): Problem = invalidCalendarIdProblem()

private fun invalidCalendarIdProblem(): Problem = Problem(
    status = 400,
    code = "VALIDATION_ERROR",
    title = "Bad Request",
    detail = "指定されたカレンダーが不正です",
    violations = listOf(Problem.Violation(field = "calendarId", code = "NOT_SELECTED_OR_INVALID")),
)

private fun unauthorizedProblem(): Problem = Problem(
    status = 401,
    code = "UNAUTHORIZED",
    title = "Unauthorized",
    detail = "認証が必要です",
)

private fun CalendarEventEnrichments.toResponse() = CalendarEventsResponse(
    calendarId = googleCalendarId,
    events = events.map { it.toResponse() },
)

private fun EventEnrichment.toResponse() = EventEnrichmentResponse(
    googleEventId = googleEventId,
    eventId = eventUuid.toString(),
    destination = destination?.toResponse(),
    congestions = congestions.map { it.toResponse() },
)

private fun EventEnrichmentDestination.toResponse() = EventDestinationResponse(name, latitude, longitude)

private fun EventEnrichmentCongestion.toResponse() = PeriodCongestionResponse(
    congestionStartTime = start.toString(),
    congestionEndTime = end.toString(),
    area = area,
    description = description,
)

@Serializable
private data class EventsResponse(val calendars: List<CalendarEventsResponse>)

@Serializable
private data class CalendarEventsResponse(val calendarId: String, val events: List<EventEnrichmentResponse>)

@Serializable
private data class EventEnrichmentResponse(
    val googleEventId: String,
    val eventId: String,
    val destination: EventDestinationResponse?,
    val congestions: List<PeriodCongestionResponse>,
)

@Serializable
private data class EventDestinationResponse(val name: String, val latitude: Double, val longitude: Double)

@Serializable
private data class PeriodCongestionResponse(
    val congestionStartTime: String,
    val congestionEndTime: String,
    val area: String,
    val description: String,
    val stores: List<StoreResponse> = emptyList(),
)

@Serializable
private data class StoreResponse(
    val name: String,
    val avgStars: Double,
    val reviewCount: Int,
    val priceRange: PriceRangeResponse,
    val address: String,
    val comment: String? = null,
)

@Serializable
private data class PriceRangeResponse(val min: Int, val max: Int)
