package com.crowdodge.app.plugins

import com.auth0.jwt.JWT
import com.crowdodge.app.configureApplicationRouting
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.command.RegisterUserDeviceUseCase
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.domain.model.FcmToken
import com.crowdodge.user.domain.model.UserDevice
import com.crowdodge.user.domain.model.UserDeviceUuid
import com.crowdodge.user.domain.repository.UserDeviceRepository
import com.crowdodge.user.infrastructure.security.hmacAlgorithm
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin
import kotlin.time.Duration.Companion.hours
import kotlin.uuid.Uuid

class UserDeviceRoutingTest : FunSpec({
    val userUuid = UserUuid(Uuid.parse("00000000-0000-0000-0000-000000000001"))
    val jwtConfig = JwtAppTokenConfig(
        issuer = "issuer",
        audience = "audience",
        secret = "01234567890123456789012345678901",
        accessTokenTtl = 1.hours,
        refreshTokenTtl = 1.hours,
    )

    fun token(): String = JWT.create()
        .withIssuer(jwtConfig.issuer)
        .withAudience(jwtConfig.audience)
        .withSubject(userUuid.value.toString())
        .sign(jwtConfig.hmacAlgorithm())

    test("POST /v1/devices は認証ユーザーの FCM token を登録し 204 を返す") {
        val repo = RouteDeviceRepository()

        testApplication {
            application { configureDeviceTest(jwtConfig, repo) }

            val response = client.post("/v1/devices") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"fcmToken":"token-1"}""")
            }

            response.status shouldBe HttpStatusCode.NoContent
            response.bodyAsText() shouldBe ""
            repo.byToken["token-1"]?.userUuid shouldBe userUuid
        }
    }

    test("POST /v1/devices は空 fcmToken を 400 Problem Details で拒否する") {
        testApplication {
            application { configureDeviceTest(jwtConfig, RouteDeviceRepository()) }

            val response = client.post("/v1/devices") {
                header(HttpHeaders.Authorization, "Bearer ${token()}")
                contentType(ContentType.Application.Json)
                setBody("""{"fcmToken":"   "}""")
            }

            response.status shouldBe HttpStatusCode.BadRequest
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
            response.bodyAsText() shouldContain """"code":"VALIDATION_ERROR""""
            response.bodyAsText() shouldContain """"code":"BLANK_FCM_TOKEN""""
        }
    }

    test("POST /v1/devices は Bearer 認証なしなら 401 Problem Details を返す") {
        testApplication {
            application { configureDeviceTest(jwtConfig, RouteDeviceRepository()) }

            val response = client.post("/v1/devices") {
                contentType(ContentType.Application.Json)
                setBody("""{"fcmToken":"token-1"}""")
            }

            response.status shouldBe HttpStatusCode.Unauthorized
            response.headers[HttpHeaders.ContentType] shouldContain "application/problem+json"
            response.bodyAsText() shouldContain """"code":"UNAUTHORIZED""""
        }
    }
})

private fun Application.configureDeviceTest(
    jwtConfig: JwtAppTokenConfig,
    devices: UserDeviceRepository,
) {
    install(Koin) {
        modules(
            module {
                single { jwtConfig }
                single { RegisterUserDeviceUseCase(devices, DeviceRouteTransactionRunner) }
            },
        )
    }
    configureSerialization()
    configureAuthentication()
    configureApplicationRouting()
}

private object DeviceRouteTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class RouteDeviceRepository : UserDeviceRepository {
    val byToken = mutableMapOf<String, UserDevice>()

    override suspend fun save(userDevice: UserDevice) {
        val existing = byToken[userDevice.fcmToken.value]
        byToken[userDevice.fcmToken.value] = existing
            ?.let { UserDevice.reconstitute(it.userDeviceUuid, userDevice.userUuid, userDevice.fcmToken) }
            ?: userDevice
    }

    override suspend fun delete(userUuid: UserUuid, userDeviceUuid: UserDeviceUuid) = Unit

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserDevice> =
        byToken.values.filter { it.userUuid == userUuid }

    override suspend fun findByFcmToken(fcmToken: FcmToken): UserDevice? = byToken[fcmToken.value]
}
