package com.crowdodge.app.plugins

import com.crowdodge.shared.kernel.ReadinessProbe
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.plugin.Koin

/**
 * ルーティング（liveness `/health` / readiness `/ready`）の結合テスト（§13 presentation）。
 * readiness の依存（[ReadinessProbe]）は fake を Koin に差し込み、ルートの分岐だけを検証する。
 */
class RoutingTest : FunSpec({

    fun Application.configureForTest(ready: Boolean) {
        install(Koin) {
            modules(
                module {
                    single<ReadinessProbe> {
                        object : ReadinessProbe {
                            override suspend fun isReady(): Boolean = ready
                        }
                    }
                },
            )
        }
        configureSerialization()
        configureRouting()
    }

    test("health は DB 状態に依らず 200 UP を返す") {
        testApplication {
            application { configureForTest(ready = false) }
            val res = client.get("/health")
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "\"status\":\"UP\""
        }
    }

    test("ready は DB 到達OKで 200 READY を返す") {
        testApplication {
            application { configureForTest(ready = true) }
            val res = client.get("/ready")
            res.status shouldBe HttpStatusCode.OK
            res.bodyAsText() shouldContain "READY"
        }
    }

    test("ready は DB 不通で 503 NOT_READY を返す") {
        testApplication {
            application { configureForTest(ready = false) }
            val res = client.get("/ready")
            res.status shouldBe HttpStatusCode.ServiceUnavailable
            res.bodyAsText() shouldContain "NOT_READY"
        }
    }
})
