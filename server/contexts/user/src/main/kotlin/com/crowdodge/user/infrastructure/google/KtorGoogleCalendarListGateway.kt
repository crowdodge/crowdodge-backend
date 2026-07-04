package com.crowdodge.user.infrastructure.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarAccessRole
import com.crowdodge.user.application.port.GoogleCalendarListGateway
import com.crowdodge.user.application.port.GoogleCalendarListItem
import com.crowdodge.user.application.service.GoogleAccessTokenProvider
import com.crowdodge.user.domain.error.UserError
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class KtorGoogleCalendarListGateway(
    private val httpClient: HttpClient,
    apiBaseUrl: String,
    private val accessTokens: GoogleAccessTokenProvider,
) : GoogleCalendarListGateway {
    private val listUrl = "${apiBaseUrl.trimEnd('/')}/calendar/v3/users/me/calendarList"
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("ReturnCount")
    override suspend fun listAll(userUuid: UserUuid): Either<UserError, List<GoogleCalendarListItem>> {
        val token = accessTokens.get(userUuid).fold({ return it.left() }, { it })
        return try {
            val result = mutableListOf<GoogleCalendarListItem>()
            val seenPageTokens = mutableSetOf<String>()
            var pageToken: String? = null
            do {
                val response = httpClient.get(listUrl) {
                    header(HttpHeaders.Authorization, "Bearer $token")
                    parameter("maxResults", MAX_RESULTS)
                    pageToken?.let { parameter("pageToken", it) }
                }
                if (!response.status.isSuccess()) return UserError.ExternalError.GoogleOAuthError.left()
                val page = json.decodeFromString<CalendarListResponse>(response.bodyAsText())
                result += page.items.orEmpty().mapNotNull { it.toDomain() }
                if (page.nextPageToken != null && !seenPageTokens.add(page.nextPageToken)) {
                    return UserError.ExternalError.GoogleOAuthError.left()
                }
                pageToken = page.nextPageToken
            } while (pageToken != null)
            result.right()
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: SocketTimeoutException) {
            UserError.ExternalError.GoogleCalendarTimeoutError.left()
        } catch (_: ConnectTimeoutException) {
            UserError.ExternalError.GoogleCalendarTimeoutError.left()
        } catch (_: HttpRequestTimeoutException) {
            UserError.ExternalError.GoogleCalendarTimeoutError.left()
        } catch (_: Exception) {
            UserError.ExternalError.GoogleOAuthError.left()
        }
    }

    private fun CalendarListEntry.toDomain(): GoogleCalendarListItem? {
        val role = when (accessRole) {
            "owner" -> GoogleCalendarAccessRole.OWNER
            "writer" -> GoogleCalendarAccessRole.WRITER
            else -> return null
        }
        return GoogleCalendarListItem(
            id = id,
            name = summaryOverride ?: summary.orEmpty(),
            color = backgroundColor,
            primary = primary ?: false,
            accessRole = role,
        )
    }

    private companion object {
        const val MAX_RESULTS = 250
    }
}

@Serializable
private data class CalendarListResponse(
    val nextPageToken: String? = null,
    val items: List<CalendarListEntry>? = null,
)

@Serializable
private data class CalendarListEntry(
    val id: String,
    val summary: String? = null,
    val summaryOverride: String? = null,
    val backgroundColor: String? = null,
    val primary: Boolean? = null,
    val accessRole: String,
)
