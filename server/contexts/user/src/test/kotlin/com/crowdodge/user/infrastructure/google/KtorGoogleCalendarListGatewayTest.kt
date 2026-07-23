package com.crowdodge.user.infrastructure.google

import arrow.core.right
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarAccessRole
import com.crowdodge.user.application.service.GoogleAccessTokenProvider
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.uuid.Uuid

class KtorGoogleCalendarListGatewayTest : FunSpec({
    val userUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000001"))

    test("maxResults 250で全ページを取得しownerとwriterだけを変換する") {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            if (requests.size == 1) {
                respond(
                    """{"nextPageToken":"next","items":[
                    {"id":"owner","summary":"Primary","backgroundColor":"#fff","primary":true,"accessRole":"owner"},
                    {"id":"reader","summary":"Read","accessRole":"reader"}]}""",
                )
            } else {
                respond("""{"items":[{"id":"writer","summaryOverride":"Work","accessRole":"writer"}]}""")
            }
        }
        val gateway = KtorGoogleCalendarListGateway(
            HttpClient(engine),
            "https://google.test",
            StaticAccessTokenProvider(),
        )

        val items = gateway.listAll(userUuid).getOrNull()!!

        requests.map { it.url.parameters["maxResults"] } shouldContainExactly listOf("250", "250")
        requests.map { it.url.parameters["pageToken"] } shouldContainExactly listOf(null, "next")
        requests.all { it.headers[HttpHeaders.Authorization] == "Bearer access" } shouldBe true
        items.map { it.id } shouldContainExactly listOf("owner", "writer")
        items[0].name shouldBe "Primary"
        items[0].color shouldBe "#fff"
        items[0].primary shouldBe true
        items[0].accessRole shouldBe GoogleCalendarAccessRole.OWNER
        items[1].name shouldBe "Work"
        items[1].accessRole shouldBe GoogleCalendarAccessRole.WRITER
    }

    test("nextPageTokenが循環したらExternalErrorで停止する") {
        var requestCount = 0
        val gateway = KtorGoogleCalendarListGateway(
            HttpClient(
                MockEngine {
                    requestCount++
                    respond("""{"nextPageToken":"loop","items":[]}""")
                },
            ),
            "https://google.test",
            StaticAccessTokenProvider(),
        )

        val result = withTimeout(500) { gateway.listAll(userUuid) }

        result.leftOrNull() shouldBe
            com.crowdodge.user.domain.error.UserError.ExternalError.GoogleOAuthError
        requestCount shouldBe 2
    }

    test("CancellationExceptionを再throwする") {
        val cancellation = CancellationException("cancel")
        val gateway = KtorGoogleCalendarListGateway(
            HttpClient(MockEngine { throw cancellation }),
            "https://google.test",
            StaticAccessTokenProvider(),
        )

        runCatching { gateway.listAll(userUuid) }.exceptionOrNull() shouldBe cancellation
    }

    test("CIO socket timeoutをGoogle Calendar timeoutへ変換する") {
        val gateway = KtorGoogleCalendarListGateway(
            HttpClient(MockEngine { throw SocketTimeoutException("socket timeout") }),
            "https://google.test",
            StaticAccessTokenProvider(),
        )

        gateway.listAll(userUuid).leftOrNull() shouldBe
            UserError.ExternalError.GoogleCalendarTimeoutError
    }

    test("CIO connect timeoutをGoogle Calendar timeoutへ変換する") {
        val gateway = KtorGoogleCalendarListGateway(
            HttpClient(MockEngine { throw ConnectTimeoutException("connect timeout") }),
            "https://google.test",
            StaticAccessTokenProvider(),
        )

        gateway.listAll(userUuid).leftOrNull() shouldBe
            UserError.ExternalError.GoogleCalendarTimeoutError
    }

    test("HttpTimeoutをGoogle Calendar timeoutへ変換する") {
        val client = HttpClient(
            MockEngine {
                kotlinx.coroutines.delay(250)
                respond("late", HttpStatusCode.OK)
            },
        ) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10.milliseconds.inWholeMilliseconds
            }
        }
        val gateway = KtorGoogleCalendarListGateway(
            client,
            "https://google.test",
            StaticAccessTokenProvider(),
        )

        gateway.listAll(userUuid).leftOrNull() shouldBe
            UserError.ExternalError.GoogleCalendarTimeoutError
    }
})

private class StaticAccessTokenProvider : GoogleAccessTokenProvider(
    credentials = object : UserGoogleCredentialRepository {
        override suspend fun findByUserUuid(userUuid: UserUuid) = null
        override suspend fun upsert(credential: com.crowdodge.user.domain.model.UserGoogleCredential) = Unit
        override suspend fun updateAccessToken(
            userUuid: UserUuid,
            accessToken: com.crowdodge.user.domain.model.GoogleAccessToken,
            accessTokenExpiresAt: kotlin.time.Instant,
        ) = Unit
    },
    refreshGateway = { error("unused") },
    transactions = object : TransactionRunner {
        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
        override suspend fun <T> readOnly(block: suspend () -> T): T = block()
    },
    clock = Clock.System,
) {
    override suspend fun get(userUuid: UserUuid) = "access".right()
}
