package com.crowdodge.shared.infra.gemini

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** Gemini Interactions API へ送る入力。 */
data class GeminiInteractionRequest(
    val input: String,
    val responseFormat: JsonObject,
    val tools: Set<GeminiInteractionTool> = emptySet(),
    val store: Boolean = false,
)

/** Gemini に利用させるツール。 */
enum class GeminiInteractionTool(val apiType: String) {
    /** Google SearchでWeb上の根拠を調査する。 */
    GoogleSearch("google_search"),
}

/** Gemini Interactions API の完了結果。 */
data class GeminiInteractionResult(
    val interactionId: String,
    val outputText: String,
    val searchQueries: List<String>,
)

/** Gemini Interactions API の応答 JSON。 */
@Serializable
internal data class GeminiInteractionResponse(
    val id: String? = null,
    val status: String? = null,
    val steps: List<GeminiInteractionStep>? = null,
)

/** Gemini Interactions API の処理ステップ。 */
@Serializable
internal data class GeminiInteractionStep(
    val type: String? = null,
    val arguments: GeminiGoogleSearchArguments? = null,
    val content: List<GeminiInteractionContent>? = null,
)

/** Google Search ステップへ渡された検索語。 */
@Serializable
internal data class GeminiGoogleSearchArguments(
    val queries: List<String>? = null,
)

/** モデル出力に含まれる内容。 */
@Serializable
internal data class GeminiInteractionContent(
    val type: String? = null,
    val text: String? = null,
)
