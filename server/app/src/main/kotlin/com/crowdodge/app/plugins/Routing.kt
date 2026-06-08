package com.crowdodge.app.plugins

import com.crowdodge.shared.kernel.ReadinessProbe
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
data class HealthResponse(val status: String, val service: String)

@Serializable
data class ReadyResponse(val status: String)

/**
 * ルーティング（§14 step1）。
 * - `/health`: liveness。プロセス生存のみを表す静的応答（DB に依存しない）。
 * - `/ready` : readiness。依存リソース（DB）への到達性を確認し、可否で 200/503 を返す。
 *
 * 各 BC の Route は contexts/<bc>/presentation に置き、ここで束ねていく。
 */
fun Application.configureRouting() {
    val readinessProbe by inject<ReadinessProbe>()
    routing {
        get("/health") {
            call.respond(HealthResponse(status = "UP", service = "crowdodge-backend"))
        }
        get("/ready") {
            if (readinessProbe.isReady()) {
                call.respond(HttpStatusCode.OK, ReadyResponse(status = "READY"))
            } else {
                call.respond(HttpStatusCode.ServiceUnavailable, ReadyResponse(status = "NOT_READY"))
            }
        }
    }
}
