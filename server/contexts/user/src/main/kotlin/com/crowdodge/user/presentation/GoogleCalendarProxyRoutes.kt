package com.crowdodge.user.presentation

import com.crowdodge.shared.infra.web.Problem
import com.crowdodge.shared.infra.web.respondProblem
import com.crowdodge.user.application.port.CalendarProxyRequest
import com.crowdodge.user.application.port.CalendarProxyResponse
import com.crowdodge.user.application.port.GOOGLE_CALENDAR_EVENT_QUERY_ALLOWLIST
import com.crowdodge.user.application.port.ProxyMethod
import com.crowdodge.user.application.query.ProxyGoogleCalendarUseCase
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.readAvailable
import org.koin.ktor.ext.inject
import java.io.ByteArrayOutputStream

fun Application.configureGoogleCalendarProxyRouting() {
    val useCase by inject<ProxyGoogleCalendarUseCase>()
    routing {
        route("/v1") {
            authenticate(APP_JWT_AUTH_NAME) {
                route("/calendars/{calendarId}/events") {
                    get { call.proxy(useCase, ProxyMethod.GET, call.pathParameter("calendarId"), null) }
                    post { call.proxy(useCase, ProxyMethod.POST, call.pathParameter("calendarId"), null) }
                    get("/{eventId}") {
                        call.proxy(
                            useCase,
                            ProxyMethod.GET,
                            call.pathParameter("calendarId"),
                            call.pathParameter("eventId"),
                        )
                    }
                    patch("/{eventId}") {
                        call.proxy(
                            useCase,
                            ProxyMethod.PATCH,
                            call.pathParameter("calendarId"),
                            call.pathParameter("eventId"),
                        )
                    }
                    delete("/{eventId}") {
                        call.proxy(
                            useCase,
                            ProxyMethod.DELETE,
                            call.pathParameter("calendarId"),
                            call.pathParameter("eventId"),
                        )
                    }
                }
            }
        }
    }
}

@Suppress("ReturnCount")
private suspend fun io.ktor.server.application.ApplicationCall.proxy(
    useCase: ProxyGoogleCalendarUseCase,
    method: ProxyMethod,
    calendarId: String?,
    eventId: String?,
) {
    val principal = principal<AuthenticatedUserPrincipal>() ?: return respondUnauthorized()
    val id = calendarId ?: return respondBadRequest()
    if (!id.isValidProxyId() || eventId?.isValidProxyId() == false) {
        return respondBadRequest()
    }
    val query = request.queryParameters.entries()
        .flatMap { (name, values) -> values.map { value -> name to value } }
    if (!query.isValidProxyQuery()) return respondBadRequest()
    val body = if (method == ProxyMethod.POST || method == ProxyMethod.PATCH) {
        if (!hasJsonContentType()) return respondBadRequest()
        receiveLimitedBody() ?: return
    } else {
        null
    }
    val response = useCase.handle(
        principal.userUuid,
        CalendarProxyRequest(
            method = method,
            calendarId = id,
            eventId = eventId,
            query = query,
            contentType = request.headers[HttpHeaders.ContentType],
            body = body,
        ),
    )
    proxyProblemFor(response.status)?.let {
        respondProblem(it)
        return
    }
    respondProxy(response)
}

private suspend fun io.ktor.server.application.ApplicationCall.receiveLimitedBody(): ByteArray? {
    val channel = receiveChannel()
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (!channel.isClosedForRead) {
        val read = channel.readAvailable(buffer)
        if (read <= 0) continue
        output.write(buffer, 0, read)
        if (output.size() > MAX_BODY_BYTES) {
            respondProblem(
                Problem(
                    status = HttpStatusCode.PayloadTooLarge.value,
                    code = "PAYLOAD_TOO_LARGE",
                    title = "Payload Too Large",
                    detail = "リクエストbodyが大きすぎます",
                ),
            )
            return null
        }
    }
    return output.toByteArray()
}

private suspend fun io.ktor.server.application.ApplicationCall.respondProxy(response: CalendarProxyResponse) {
    response.etag?.let { this.response.header(HttpHeaders.ETag, it) }
    val contentType = response.contentType?.let { runCatching { ContentType.parse(it) }.getOrNull() }
    respondBytes(response.body, contentType, HttpStatusCode.fromValue(response.status))
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
            code = "INVALID_CALENDAR_PROXY_REQUEST",
            title = "Bad Request",
            detail = "リクエストが不正です",
        ),
    )
}

private fun io.ktor.server.application.ApplicationCall.pathParameter(name: String): String? = parameters[name]

private fun String.isValidProxyId(): Boolean = isNotBlank() && length <= MAX_ID_LENGTH

private fun List<Pair<String, String>>.isValidProxyQuery(): Boolean =
    size <= MAX_QUERY_COUNT &&
        all { (name, value) ->
            name in GOOGLE_CALENDAR_EVENT_QUERY_ALLOWLIST && value.length <= MAX_QUERY_VALUE_LENGTH
        } &&
        sumOf { (name, value) -> name.length + value.length } <= MAX_QUERY_TOTAL_LENGTH

private fun io.ktor.server.application.ApplicationCall.hasJsonContentType(): Boolean {
    val value = request.headers[HttpHeaders.ContentType]
    return value?.let { runCatching { ContentType.parse(it) }.getOrNull() }
        ?.match(ContentType.Application.Json) == true
}

private const val MAX_BODY_BYTES = 1024 * 1024
private const val MAX_ID_LENGTH = 2048
private const val MAX_QUERY_COUNT = 50
private const val MAX_QUERY_TOTAL_LENGTH = 16384
private const val MAX_QUERY_VALUE_LENGTH = 4096
private const val READ_BUFFER_BYTES = 8192
