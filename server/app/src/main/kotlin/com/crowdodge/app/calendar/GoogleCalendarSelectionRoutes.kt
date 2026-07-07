package com.crowdodge.app.calendar

import com.crowdodge.event.domain.error.EventError
import com.crowdodge.shared.infra.web.Problem
import com.crowdodge.shared.infra.web.respondProblem
import com.crowdodge.user.application.port.GoogleCalendarAccessRole
import com.crowdodge.user.application.service.SelectableGoogleCalendar
import com.crowdodge.user.application.service.UserCalendarSelectionService
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.presentation.APP_JWT_AUTH_NAME
import com.crowdodge.user.presentation.AuthenticatedUserPrincipal
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

fun Application.configureGoogleCalendarSelectionRouting() {
    val selections by inject<UserCalendarSelectionService>()
    val coordinator by inject<ReplaceGoogleCalendarSelectionCoordinator>()
    routing {
        route("/v1") {
            authenticate(APP_JWT_AUTH_NAME) {
                route("/users/me/calendars") {
                    get {
                        val principal = call.principal<AuthenticatedUserPrincipal>()
                            ?: return@get call.respondUnauthorized()
                        selections.listAvailable(principal.userUuid).fold(
                            ifLeft = { call.respondUserError(it) },
                            ifRight = {
                                call.respond(
                                    GoogleCalendarsResponse(it.map { calendar -> calendar.toResponse() }),
                                )
                            },
                        )
                    }
                    put {
                        val principal = call.principal<AuthenticatedUserPrincipal>()
                            ?: return@put call.respondUnauthorized()
                        val request = runCatching { call.receive<ReplaceGoogleCalendarSelectionRequest>() }
                            .getOrElse { return@put call.respondBadRequest() }
                        coordinator.execute(principal.userUuid, request.calendarIds).fold(
                            ifLeft = { call.respondSelectionError(it) },
                            ifRight = { call.respondText("", status = HttpStatusCode.NoContent) },
                        )
                    }
                }
            }
        }
    }
}

@Serializable
private data class GoogleCalendarsResponse(
    val calendars: List<GoogleCalendarResponse>,
)

@Serializable
private data class GoogleCalendarResponse(
    val id: String,
    val name: String,
    val color: String?,
    val primary: Boolean,
    val accessRole: String,
    val selected: Boolean,
)

@Serializable
private data class ReplaceGoogleCalendarSelectionRequest(
    val calendarIds: List<String>,
)

private fun SelectableGoogleCalendar.toResponse(): GoogleCalendarResponse =
    GoogleCalendarResponse(
        id = id,
        name = name,
        color = color,
        primary = primary,
        accessRole = accessRole.toResponseValue(),
        selected = selected,
    )

private fun GoogleCalendarAccessRole.toResponseValue(): String = when (this) {
    GoogleCalendarAccessRole.OWNER -> "owner"
    GoogleCalendarAccessRole.WRITER -> "writer"
    GoogleCalendarAccessRole.READER -> "reader"
}

private suspend fun io.ktor.server.application.ApplicationCall.respondSelectionError(
    error: GoogleCalendarSelectionError,
) {
    when (error) {
        is GoogleCalendarSelectionError.Event ->
            if (error.cause is EventError.ExternalError.GoogleCalendarTimeoutError) {
                respondGoogleCalendarTimeout()
            } else {
                respondGoogleCalendarBadGateway()
            }
        is GoogleCalendarSelectionError.User -> respondUserError(error.cause)
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondUserError(error: UserError) {
    when (error) {
        is UserError.AuthenticationError -> respondGoogleReauthRequired()
        is UserError.AuthorizationError -> respondProblem(
            Problem(
                status = HttpStatusCode.Forbidden.value,
                code = error.code,
                title = "Forbidden",
                detail = "Google Calendar への権限が不足しています",
            ),
        )
        is UserError.ValidationError -> respondProblem(
            Problem(
                status = HttpStatusCode.BadRequest.value,
                code = error.code,
                title = "Bad Request",
                detail = "リクエストが不正です",
            ),
        )
        is UserError.ExternalError.GoogleCalendarTimeoutError -> respondGoogleCalendarTimeout()
        is UserError.ExternalError.GoogleOAuthError -> respondGoogleCalendarBadGateway()
        is UserError.ConflictError -> respondProblem(
            Problem(
                status = HttpStatusCode.BadGateway.value,
                code = error.code,
                title = "Bad Gateway",
                detail = "Google Calendar selection could not be committed",
            ),
        )
    }
}

private suspend fun io.ktor.server.application.ApplicationCall.respondGoogleCalendarTimeout() {
    respondProblem(
        Problem(
            status = HttpStatusCode.GatewayTimeout.value,
            code = "GOOGLE_CALENDAR_TIMEOUT",
            title = "Gateway Timeout",
            detail = "Google Calendar request timed out",
        ),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondGoogleCalendarBadGateway() {
    respondProblem(
        Problem(
            status = HttpStatusCode.BadGateway.value,
            code = "GOOGLE_CALENDAR_ERROR",
            title = "Bad Gateway",
            detail = "Google Calendar request failed",
        ),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondGoogleReauthRequired() {
    respondProblem(
        Problem(
            status = HttpStatusCode.Unauthorized.value,
            code = "GOOGLE_REAUTH_REQUIRED",
            title = "Unauthorized",
            detail = "Google の再認可が必要です",
        ),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondUnauthorized() {
    respondProblem(
        Problem(
            status = HttpStatusCode.Unauthorized.value,
            code = "UNAUTHORIZED",
            title = "Unauthorized",
            detail = "認証が必要です",
        ),
    )
}

private suspend fun io.ktor.server.application.ApplicationCall.respondBadRequest() {
    respondProblem(
        Problem(
            status = HttpStatusCode.BadRequest.value,
            code = "INVALID_GOOGLE_CALENDAR_SELECTION_REQUEST",
            title = "Bad Request",
            detail = "リクエストが不正です",
        ),
    )
}
