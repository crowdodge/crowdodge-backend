package com.crowdodge.app.notification

import com.crowdodge.shared.infra.gemini.GeminiInteractionsConfig
import io.ktor.server.application.ApplicationEnvironment

/** アプリケーション設定から Gemini Interactions API の設定を読み込む。 */
fun ApplicationEnvironment.geminiInteractionsConfig(): GeminiInteractionsConfig {
    val config = config.config("crowdodge.congestion.gemini")
    return GeminiInteractionsConfig(
        apiBaseUrl = config.property("apiBaseUrl").getString(),
        apiKey = config.property("apiKey").getString(),
        maxAttempts = 2,
    )
}
