package com.crowdodge.event.infrastructure.google

import com.crowdodge.event.application.port.CalendarConnection
import com.crowdodge.event.application.port.CalendarSyncBatch
import com.crowdodge.event.application.port.CalendarSyncFetchResult
import com.crowdodge.event.domain.error.EventError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

class GoogleCalendarGatewayTest : FunSpec({
    test("fullSync は nextPageToken がなくなるまで全ページを取得し全ページで同じ timeMin/timeMax を送る") {
        val requestedUrls = mutableListOf<String>()
        val httpClient = HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                respond(
                    content = when (requestedUrls.size) {
                        1 -> eventsPage(
                            items = listOf(googleEvent("event-1")),
                            nextPageToken = "page-2",
                            nextSyncToken = "not-final-token",
                        )
                        else -> eventsPage(
                            items = listOf(googleEvent("event-2")),
                            nextSyncToken = "final-token",
                        )
                    },
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val gateway = gateway(httpClient)
        val windowStart = Instant.parse("2026-07-01T00:00:00Z")
        val windowEnd = Instant.parse("2026-10-01T00:00:00Z")

        val batch = gateway.fullSync(FixedConnection, windowStart, windowEnd)
            .fold({ error("unexpected error: $it") }, { it })

        batch.upserts.map { it.googleEventId.value } shouldBe listOf("event-1", "event-2")
        batch.nextSyncToken shouldBe "final-token"
        requestedUrls.size shouldBe 2
        requestedUrls.map { queryParam(it, "timeMin") } shouldBe listOf(windowStart.toString(), windowStart.toString())
        requestedUrls.map { queryParam(it, "timeMax") } shouldBe listOf(windowEnd.toString(), windowEnd.toString())
        queryParam(requestedUrls[0], "pageToken") shouldBe null
        queryParam(requestedUrls[1], "pageToken") shouldBe "page-2"
    }

    test("incrementalSync は syncToken と pageToken だけを送り timeMin/timeMax を送らず最終ページの nextSyncToken を返す") {
        val requestedUrls = mutableListOf<String>()
        val httpClient = HttpClient(
            MockEngine { request ->
                requestedUrls += request.url.toString()
                respond(
                    content = when (requestedUrls.size) {
                        1 -> eventsPage(nextPageToken = "page-2", nextSyncToken = "not-final-token")
                        else -> eventsPage(nextSyncToken = "incremental-final-token")
                    },
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val gateway = gateway(httpClient)

        val result = gateway.incrementalSync(FixedConnection, syncToken = "sync-token")
            .fold({ error("unexpected error: $it") }, { it })

        result shouldBe CalendarSyncFetchResult.Events(
            CalendarSyncBatch(
                upserts = emptyList(),
                cancellations = emptyList(),
                nextSyncToken = "incremental-final-token",
            ),
        )
        requestedUrls.size shouldBe 2
        requestedUrls.map { queryParam(it, "syncToken") } shouldBe listOf("sync-token", "sync-token")
        requestedUrls.map { queryParam(it, "pageToken") } shouldBe listOf(null, "page-2")
        requestedUrls.map { queryParam(it, "timeMin") } shouldBe listOf(null, null)
        requestedUrls.map { queryParam(it, "timeMax") } shouldBe listOf(null, null)
    }

    test("incrementalSync は Google Calendar の 410 Gone を SyncTokenExpired に変換する") {
        val httpClient = HttpClient(
            MockEngine {
                respond(
                    content = """{"error":{"code":410}}""",
                    status = HttpStatusCode.Gone,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val gateway = gateway(httpClient)

        gateway.incrementalSync(FixedConnection, syncToken = "expired-token")
            .fold({ error("unexpected error: $it") }, { it }) shouldBe CalendarSyncFetchResult.SyncTokenExpired
    }

    test("non-cancelled event の mapping に失敗したら同期を失敗させる") {
        val httpClient = HttpClient(
            MockEngine {
                respond(
                    content = """
                        {
                          "items": [
                            {
                              "id": "broken-event",
                              "status": "confirmed",
                              "start": { "dateTime": "2026-06-27T10:00:00Z" }
                            }
                          ],
                          "nextSyncToken": "next-token"
                        }
                    """.trimIndent(),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            },
        )
        val gateway = GoogleCalendarGateway(
            config = GoogleCalendarConfig(
                apiBaseUrl = "https://calendar.example.test",
                fullSyncWindowDays = 90,
            ),
            httpClient = httpClient,
        )

        gateway.fullSync(
            FixedConnection,
            windowStart = Instant.parse("2026-07-01T00:00:00Z"),
            windowEnd = Instant.parse("2026-10-01T00:00:00Z"),
        ).leftOrNull() shouldBe
            EventError.ExternalError.GoogleCalendarError
    }

    test("HTTP request の cancellation は GoogleCalendarError に変換せず再送出する") {
        val httpClient = HttpClient(
            MockEngine {
                throw CancellationException("cancelled")
            },
        )
        val gateway = GoogleCalendarGateway(
            config = GoogleCalendarConfig(
                apiBaseUrl = "https://calendar.example.test",
                fullSyncWindowDays = 90,
            ),
            httpClient = httpClient,
        )

        shouldThrow<CancellationException> {
            gateway.fullSync(
                FixedConnection,
                windowStart = Instant.parse("2026-07-01T00:00:00Z"),
                windowEnd = Instant.parse("2026-10-01T00:00:00Z"),
            )
        }
    }

    test("fullSyncのHttpTimeoutをGoogle Calendar timeoutへ変換する") {
        val httpClient = HttpClient(
            MockEngine {
                kotlinx.coroutines.delay(250)
                respond("late", HttpStatusCode.OK)
            },
        ) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10.milliseconds.inWholeMilliseconds
            }
        }

        gateway(httpClient).fullSync(
            FixedConnection,
            windowStart = Instant.parse("2026-07-01T00:00:00Z"),
            windowEnd = Instant.parse("2026-10-01T00:00:00Z"),
        ).leftOrNull() shouldBe EventError.ExternalError.GoogleCalendarTimeoutError
    }
})

private val FixedConnection = CalendarConnection(calendarId = "primary", accessToken = "access-token")

private fun gateway(httpClient: HttpClient): GoogleCalendarGateway =
    GoogleCalendarGateway(
        config = GoogleCalendarConfig(
            apiBaseUrl = "https://calendar.example.test",
            fullSyncWindowDays = 90,
        ),
        httpClient = httpClient,
    )

private fun eventsPage(
    items: List<String> = emptyList(),
    nextPageToken: String? = null,
    nextSyncToken: String? = null,
): String {
    val fields = buildList {
        add(""""items": [${items.joinToString(",")}]""")
        if (nextPageToken != null) add(""""nextPageToken": "$nextPageToken"""")
        if (nextSyncToken != null) add(""""nextSyncToken": "$nextSyncToken"""")
    }
    return "{${fields.joinToString(",")}}"
}

private fun googleEvent(id: String): String =
    """
        {
          "id": "$id",
          "status": "confirmed",
          "summary": "title-$id",
          "start": { "dateTime": "2026-07-01T10:00:00Z" },
          "end": { "dateTime": "2026-07-01T11:00:00Z" }
        }
    """.trimIndent()

private fun queryParam(url: String, name: String): String? =
    java.net.URI(url).rawQuery
        ?.split("&")
        ?.mapNotNull { pair ->
            val parts = pair.split("=", limit = 2)
            if (parts.firstOrNull() != name) return@mapNotNull null
            java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, Charsets.UTF_8.name())
        }
        ?.firstOrNull()
