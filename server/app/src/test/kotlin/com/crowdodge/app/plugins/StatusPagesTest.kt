package com.crowdodge.app.plugins

import com.crowdodge.shared.kernel.ReadinessProbe
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

class StatusPagesTest : FunSpec({

    fun Application.configureForTest() {
        install(Koin) {
            modules(
                module {
                    single<ReadinessProbe> {
                        object : ReadinessProbe {
                            override suspend fun isReady(): Boolean = false
                        }
                    }
                },
            )
        }
        configureStatusPages()
        configureSerialization()
        configureRouting()
        routing {
            get("/known") {
                call.respondText("ok")
            }
        }
    }

    test("存在しないrouteの404はProblemを返す") {
        testApplication {
            application { configureForTest() }

            val response = client.get("/unknown")

            response.status shouldBe HttpStatusCode.NotFound
            response.contentType() shouldBe ContentType.Application.ProblemJson
            response.bodyAsText() shouldContain "\"code\":\"NOT_FOUND\""
        }
    }

    test("未対応methodの405はProblemを返す") {
        testApplication {
            application { configureForTest() }

            val response = client.post("/known")

            response.status shouldBe HttpStatusCode.MethodNotAllowed
            response.contentType() shouldBe ContentType.Application.ProblemJson
            response.bodyAsText() shouldContain "\"code\":\"METHOD_NOT_ALLOWED\""
        }
    }

    test("readyの503は運用レスポンスを維持する") {
        testApplication {
            application { configureForTest() }

            val response = client.get("/ready")

            response.status shouldBe HttpStatusCode.ServiceUnavailable
            response.contentType()?.match(ContentType.Application.Json) shouldBe true
            response.bodyAsText() shouldContain "\"status\":\"NOT_READY\""
        }
    }
})
