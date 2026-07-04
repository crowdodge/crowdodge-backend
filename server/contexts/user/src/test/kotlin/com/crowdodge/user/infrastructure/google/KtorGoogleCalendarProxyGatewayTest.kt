package com.crowdodge.user.infrastructure.google

import com.crowdodge.user.application.port.CalendarProxyRequest
import com.crowdodge.user.application.port.ProxyMethod
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class KtorGoogleCalendarProxyGatewayTest : FunSpec({
    test("list/detail と更新系を固定URLへ転送し許可queryとresponse headerを維持する") {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                content = ByteReadChannel("""{"nextPageToken":"next"}"""),
                status = HttpStatusCode.Accepted,
                headers = headersOf(
                    HttpHeaders.ContentType to listOf("application/json; charset=UTF-8"),
                    HttpHeaders.ETag to listOf("\"etag\""),
                    HttpHeaders.SetCookie to listOf("secret=value"),
                ),
            )
        }
        val gateway = KtorGoogleCalendarProxyGateway(HttpClient(engine), TEST_API_BASE_URL)

        val cases = listOf(
            CalendarProxyRequest(
                ProxyMethod.GET,
                "primary/a",
                null,
                listOf("timeMin" to "2026-01-01T00:00:00Z"),
                null,
                null,
            ),
            CalendarProxyRequest(ProxyMethod.GET, "cal id", "event/id", emptyList(), null, null),
            CalendarProxyRequest(
                ProxyMethod.POST,
                "cal id",
                null,
                emptyList(),
                "application/json",
                "{}".encodeToByteArray(),
            ),
            CalendarProxyRequest(
                ProxyMethod.PATCH,
                "cal id",
                "event/id",
                emptyList(),
                "application/json",
                """{"x":1}""".encodeToByteArray(),
            ),
            CalendarProxyRequest(ProxyMethod.DELETE, "cal id", "event/id", emptyList(), null, null),
        )

        val responses = cases.map { gateway.proxy(it, "access-token") { null } }

        requests.map { it.method.value } shouldContainExactly listOf("GET", "GET", "POST", "PATCH", "DELETE")
        requests[0].url.toString() shouldBe
            "$TEST_API_BASE_URL/calendar/v3/calendars/primary%2Fa/events?timeMin=2026-01-01T00%3A00%3A00Z"
        requests[1].url.toString() shouldBe
            "$TEST_API_BASE_URL/calendar/v3/calendars/cal%20id/events/event%2Fid"
        requests.all { it.headers[HttpHeaders.Authorization] == "Bearer access-token" } shouldBe true
        requests[2].body.contentType?.toString() shouldBe "application/json"
        requests[3].body.toByteArray().decodeToString() shouldBe """{"x":1}"""
        responses.all { it.status == 202 } shouldBe true
        responses.first().contentType shouldBe "application/json; charset=UTF-8"
        responses.first().etag shouldBe "\"etag\""
        responses.first().body.decodeToString() shouldBe """{"nextPageToken":"next"}"""
    }

    test("Google 401ではrefresh後に同じrequestを一度だけ再送する") {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            if (requests.size == 1) {
                respond("unauthorized", HttpStatusCode.Unauthorized)
            } else {
                respond("ok", HttpStatusCode.OK)
            }
        }
        val gateway = KtorGoogleCalendarProxyGateway(HttpClient(engine), TEST_API_BASE_URL)
        var refreshCount = 0
        val request = CalendarProxyRequest(
            ProxyMethod.PATCH,
            "primary",
            "event",
            listOf("maxResults" to "10"),
            "application/json",
            "{}".encodeToByteArray(),
        )

        val response = gateway.proxy(request, "old") {
            refreshCount++
            "new"
        }

        response.status shouldBe 200
        refreshCount shouldBe 1
        requests.size shouldBe 2
        requests[0].headers[HttpHeaders.Authorization] shouldBe "Bearer old"
        requests[1].headers[HttpHeaders.Authorization] shouldBe "Bearer new"
        requests[0].url shouldBe requests[1].url
        requests[0].body.toByteArray().decodeToString() shouldBe requests[1].body.toByteArray().decodeToString()
    }

    test("最終401はそのまま返しrefreshと再送は一度に制限する") {
        var calls = 0
        val gateway = KtorGoogleCalendarProxyGateway(
            HttpClient(
                MockEngine {
                    calls++
                    respond("unauthorized", HttpStatusCode.Unauthorized)
                },
            ),
            TEST_API_BASE_URL,
        )
        var refreshCount = 0

        val response = gateway.proxy(
            CalendarProxyRequest(ProxyMethod.GET, "primary", null, emptyList(), null, null),
            "old",
        ) {
            refreshCount++
            "new"
        }

        response.status shouldBe 401
        calls shouldBe 2
        refreshCount shouldBe 1
    }

    test("接続失敗を502へ変換する") {
        val gateway = KtorGoogleCalendarProxyGateway(
            HttpClient(MockEngine { throw java.net.ConnectException("down") }),
            TEST_API_BASE_URL,
        )

        gateway.proxy(
            CalendarProxyRequest(ProxyMethod.GET, "primary", null, emptyList(), null, null),
            "token",
        ) { null }.status shouldBe 502
    }

    test("CIO socket timeoutを504へ変換する") {
        val gateway = KtorGoogleCalendarProxyGateway(
            HttpClient(MockEngine { throw SocketTimeoutException("socket timeout") }),
            TEST_API_BASE_URL,
        )

        gateway.proxy(
            CalendarProxyRequest(ProxyMethod.GET, "primary", null, emptyList(), null, null),
            "token",
        ) { null }.status shouldBe 504
    }

    test("CIO connect timeoutを504へ変換する") {
        val gateway = KtorGoogleCalendarProxyGateway(
            HttpClient(MockEngine { throw ConnectTimeoutException("connect timeout") }),
            TEST_API_BASE_URL,
        )

        gateway.proxy(
            CalendarProxyRequest(ProxyMethod.GET, "primary", null, emptyList(), null, null),
            "token",
        ) { null }.status shouldBe 504
    }

    test("HttpTimeoutが発火すると504へ変換する") {
        val client = HttpClient(
            MockEngine {
                kotlinx.coroutines.delay(250)
                respond("late", HttpStatusCode.OK)
            },
        ) {
            install(HttpTimeout)
        }
        val gateway = KtorGoogleCalendarProxyGateway(
            client,
            TEST_API_BASE_URL,
            requestTimeout = 10.milliseconds,
        )

        val response = gateway.proxy(
            CalendarProxyRequest(ProxyMethod.GET, "primary", null, emptyList(), null, null),
            "token",
        ) { null }

        response.status shouldBe HttpStatusCode.GatewayTimeout.value
    }

    test("外部からのCancellationExceptionは再throwする") {
        val cancellation = CancellationException("cancelled")
        val gateway = KtorGoogleCalendarProxyGateway(
            HttpClient(MockEngine { throw cancellation }),
            TEST_API_BASE_URL,
        )

        val thrown = runCatching {
            gateway.proxy(
                CalendarProxyRequest(ProxyMethod.GET, "primary", null, emptyList(), null, null),
                "token",
            ) { null }
        }.exceptionOrNull()

        thrown shouldBe cancellation
    }

    test("外側scopeのtimeout cancellationは再throwする") {
        val gateway = KtorGoogleCalendarProxyGateway(
            HttpClient(
                MockEngine {
                    kotlinx.coroutines.delay(100)
                    respond("late", HttpStatusCode.OK)
                },
            ),
            TEST_API_BASE_URL,
            requestTimeout = 1.seconds,
        )

        val thrown = runCatching {
            withTimeout(1.milliseconds) {
                gateway.proxy(
                    CalendarProxyRequest(ProxyMethod.GET, "primary", null, emptyList(), null, null),
                    "token",
                ) { null }
            }
        }.exceptionOrNull()

        thrown.shouldBeInstanceOf<kotlinx.coroutines.TimeoutCancellationException>()
    }

    test("response bodyは1MiB超を検出した時点で読込を停止する") {
        val responseChannel = ByteReadChannel(ByteArray(1024 * 1024 + 128))

        val body = readGoogleResponseBody(responseChannel)

        body shouldBe null
        responseChannel.closedCause?.message shouldBe "Google Calendar response body exceeds 1 MiB"
    }

    test("allowlist外queryは400としてGoogleへ送信しない") {
        var calls = 0
        val gateway = KtorGoogleCalendarProxyGateway(
            HttpClient(
                MockEngine {
                    calls++
                    respond("unexpected", HttpStatusCode.OK)
                },
            ),
            TEST_API_BASE_URL,
        )

        val response = gateway.proxy(
            CalendarProxyRequest(
                ProxyMethod.GET,
                "primary",
                null,
                listOf("sendUpdates" to "all"),
                null,
                null,
            ),
            "token",
        ) { null }

        response.status shouldBe 400
        calls shouldBe 0
    }
})

private suspend fun io.ktor.http.content.OutgoingContent.toByteArray(): ByteArray =
    when (this) {
        is io.ktor.http.content.OutgoingContent.ByteArrayContent -> bytes()
        else -> byteArrayOf()
    }

private const val TEST_API_BASE_URL = "https://calendar.example.test"
