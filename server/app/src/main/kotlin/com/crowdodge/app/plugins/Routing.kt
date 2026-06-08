package com.crowdodge.app.plugins

import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(val status: String, val service: String)

/**
 * ルーティング（§14 step1: ヘルスチェック）。
 * 各 BC の Route は contexts/<bc>/presentation に置き、ここで束ねていく。
 */
fun Application.configureRouting() {
    routing {
        get("/health") {
            call.respond(HealthResponse(status = "UP", service = "crowdodge-backend"))
        }
    }
}
