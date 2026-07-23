package com.crowdodge.app

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication

class GoogleCalendarRoutingIntegrationTest : FunSpec({
    test("Webhook routeとGoogleカレンダー選択routeを起動できる") {
        testApplication {
            environment {
                config = routingTestConfig()
            }

            application {
                module()
            }

            client.post("/webhooks/google-calendar").status shouldBe HttpStatusCode.BadRequest
            client.get("/v1/users/me/calendars").status shouldBe HttpStatusCode.Unauthorized
        }
    }
})

private fun routingTestConfig() = MapApplicationConfig(
    "crowdodge.database.host" to "localhost",
    "crowdodge.database.port" to "5432",
    "crowdodge.database.name" to "crowdodge",
    "crowdodge.database.username" to "crowdodge",
    "crowdodge.database.password" to "crowdodge",
    "crowdodge.database.sslMode" to "disable",
    "crowdodge.database.pgbouncer" to "false",
    "crowdodge.googleCalendar.apiBaseUrl" to "https://google.test",
    "crowdodge.googleCalendar.webhookUrl" to "https://example.test/webhooks/google-calendar",
    "crowdodge.googleCalendar.channelToken" to "channel-token",
    "crowdodge.googleCalendar.fullSyncWindowDays" to "90",
    "crowdodge.googleCalendar.oauthTokenUrl" to "https://google.test/token",
    "crowdodge.googleCalendar.oauthJwksUrl" to "https://google.test/jwks",
    "crowdodge.googleCalendar.oauthClientId" to "client-id",
    "crowdodge.googleCalendar.oauthClientSecret" to "client-secret",
    "crowdodge.googleCalendar.tokenEncryptionKey" to "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
    "crowdodge.auth.jwt.secret" to "01234567890123456789012345678901",
    "crowdodge.auth.jwt.issuer" to "crowdodge-api",
    "crowdodge.auth.jwt.audience" to "crowdodge-app",
    "crowdodge.auth.jwt.accessTokenTtlSeconds" to "900",
    "crowdodge.auth.jwt.refreshTokenTtlSeconds" to "2592000",
)
