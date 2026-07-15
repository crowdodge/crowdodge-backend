package com.crowdodge.shared.infra.gemini

/** Gemini Interactions API の接続・再試行設定。 */
data class GeminiInteractionsConfig(
    val apiBaseUrl: String,
    val apiKey: String,
    val model: String = "gemini-3.5-flash",
    val maxAttempts: Int = 2,
) {
    init {
        require(apiBaseUrl.isNotBlank()) { "Gemini API base URL is required" }
        require(apiKey.isNotBlank()) { "GEMINI_API_KEY is required" }
        require(model.isNotBlank()) { "Gemini model is required" }
        require(maxAttempts in 1..2) { "maxAttempts must be between 1 and 2" }
    }
}
