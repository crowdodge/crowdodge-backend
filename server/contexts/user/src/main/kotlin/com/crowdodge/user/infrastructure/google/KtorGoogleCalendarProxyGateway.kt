package com.crowdodge.user.infrastructure.google

import com.crowdodge.user.application.port.CalendarProxyRequest
import com.crowdodge.user.application.port.CalendarProxyResponse
import com.crowdodge.user.application.port.GOOGLE_CALENDAR_EVENT_QUERY_ALLOWLIST
import com.crowdodge.user.application.port.GoogleCalendarProxyGateway
import com.crowdodge.user.application.port.ProxyMethod
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.timeout
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class KtorGoogleCalendarProxyGateway(
    private val httpClient: HttpClient,
    apiBaseUrl: String,
    private val requestTimeout: Duration = 10.seconds,
) : GoogleCalendarProxyGateway {
    private val calendarApiBaseUrl = "${apiBaseUrl.trimEnd('/')}/calendar/v3/calendars"

    @Suppress("ReturnCount")
    override suspend fun proxy(
        request: CalendarProxyRequest,
        accessToken: String,
        refreshAccessToken: suspend () -> String?,
    ): CalendarProxyResponse {
        if (request.query.any { (name, _) -> name !in GOOGLE_CALENDAR_EVENT_QUERY_ALLOWLIST }) {
            return CalendarProxyResponse(HttpStatusCode.BadRequest.value, null, byteArrayOf())
        }
        val first = execute(request, accessToken)
        if (first.status != HttpStatusCode.Unauthorized.value) return first
        val refreshedToken = refreshAccessToken() ?: return first
        return execute(request, refreshedToken)
    }

    private suspend fun execute(request: CalendarProxyRequest, accessToken: String): CalendarProxyResponse =
        try {
            val response = httpClient.request(buildUrl(request)) {
                method = request.method.toHttpMethod()
                headers.append(HttpHeaders.Authorization, "Bearer $accessToken")
                timeout {
                    requestTimeoutMillis = requestTimeout.inWholeMilliseconds
                    connectTimeoutMillis = requestTimeout.inWholeMilliseconds
                    socketTimeoutMillis = requestTimeout.inWholeMilliseconds
                }
                request.body?.let { setBody(it) }
                request.contentType?.let { contentType(ContentType.parse(it)) }
            }
            val body = readGoogleResponseBody(response.bodyAsChannel())
                ?: return CalendarProxyResponse(
                    HttpStatusCode.BadGateway.value,
                    null,
                    byteArrayOf(),
                )
            CalendarProxyResponse(
                status = response.status.value,
                contentType = response.headers[HttpHeaders.ContentType],
                body = body,
                etag = response.headers[HttpHeaders.ETag],
            )
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: SocketTimeoutException) {
            CalendarProxyResponse(HttpStatusCode.GatewayTimeout.value, null, byteArrayOf())
        } catch (_: ConnectTimeoutException) {
            CalendarProxyResponse(HttpStatusCode.GatewayTimeout.value, null, byteArrayOf())
        } catch (_: HttpRequestTimeoutException) {
            CalendarProxyResponse(HttpStatusCode.GatewayTimeout.value, null, byteArrayOf())
        } catch (_: Exception) {
            CalendarProxyResponse(HttpStatusCode.BadGateway.value, null, byteArrayOf())
        }

    private fun buildUrl(request: CalendarProxyRequest): String {
        val calendarId = request.calendarId.encodeURLPathPart()
        val eventPath = request.eventId?.let { "/${it.encodeURLPathPart()}" }.orEmpty()
        return URLBuilder("$calendarApiBaseUrl/$calendarId/events$eventPath").apply {
            request.query.forEach { (name, value) -> parameters.append(name, value) }
        }.buildString()
    }

    private fun ProxyMethod.toHttpMethod(): HttpMethod = when (this) {
        ProxyMethod.GET -> HttpMethod.Get
        ProxyMethod.POST -> HttpMethod.Post
        ProxyMethod.PATCH -> HttpMethod.Patch
        ProxyMethod.DELETE -> HttpMethod.Delete
    }
}

internal suspend fun readGoogleResponseBody(
    channel: io.ktor.utils.io.ByteReadChannel,
): ByteArray? {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(READ_BUFFER_BYTES)
    while (!channel.isClosedForRead) {
        val remaining = MAX_BODY_BYTES + 1 - output.size()
        val read = channel.readAvailable(buffer, 0, minOf(buffer.size, remaining))
        if (read <= 0) continue
        output.write(buffer, 0, read)
        if (output.size() > MAX_BODY_BYTES) {
            channel.cancel(CancellationException("Google Calendar response body exceeds 1 MiB"))
            return null
        }
    }
    return output.toByteArray()
}

private const val MAX_BODY_BYTES = 1024 * 1024
private const val READ_BUFFER_BYTES = 8192
