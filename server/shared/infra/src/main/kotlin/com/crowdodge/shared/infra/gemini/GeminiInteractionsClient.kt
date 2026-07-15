@file:Suppress("MagicNumber", "ThrowsCount", "TooGenericExceptionCaught", "TooManyFunctions")

package com.crowdodge.shared.infra.gemini

import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/** Gemini Interactions API の呼び出しに失敗したことを表す。 */
class GeminiInteractionsException(
    message: String,
    cause: Throwable? = null,
    val isRetryable: Boolean = false,
) : RuntimeException(message, cause)

/** Gemini Interactions API を呼び出し、一時的な失敗を再試行する。 */
class GeminiInteractionsClient(
    private val httpClient: HttpClient,
    private val config: GeminiInteractionsConfig,
    private val sleeper: suspend (Duration) -> Unit = { kotlinx.coroutines.delay(it) },
    private val now: () -> Instant = { Clock.System.now() },
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** Gemini へリクエストを送り、完了した応答を返す。 */
    suspend fun interact(request: GeminiInteractionRequest): GeminiInteractionResult {
        var attempt = 0
        while (true) {
            try {
                return requestOnce(request)
            } catch (exception: kotlinx.coroutines.CancellationException) {
                throw exception
            } catch (exception: Exception) {
                val retryAfter = (exception as? RetryableGeminiFailure)?.retryAfter
                if (!isRetryable(exception)) throw exception
                if (attempt + 1 >= config.maxAttempts) {
                    throw GeminiInteractionsException(
                        "Gemini request failed after ${config.maxAttempts} attempts",
                        exception,
                        isRetryable = true,
                    )
                }
                sleeper(retryAfter ?: exponentialBackoff(attempt))
                attempt += 1
            }
        }
    }

    private suspend fun requestOnce(request: GeminiInteractionRequest): GeminiInteractionResult {
        val response = httpClient.post("${config.apiBaseUrl.trimEnd('/')}/v1/interactions") {
            contentType(ContentType.Application.Json)
            header("x-goog-api-key", config.apiKey)
            setBody(Json.encodeToString(JsonElement.serializer(), requestBody(request)))
        }
        if (response.status.value !in 200..299) throw httpFailure(response)

        val interaction = runCatching {
            json.decodeFromString<GeminiInteractionResponse>(response.bodyAsText())
        }.getOrElse {
            throw GeminiInteractionsException("Gemini interaction response is not valid JSON", it)
        }
        if (interaction.status != "completed") {
            throw GeminiInteractionsException("Gemini interaction is not completed")
        }
        val interactionId = interaction.id?.takeIf { it.isNotBlank() }
            ?: throw GeminiInteractionsException("Gemini interaction ID is missing")

        val finalModelOutput = interaction.steps.orEmpty()
            .asReversed()
            .firstOrNull { it.type == "model_output" }
        val text = finalModelOutput?.content.orEmpty()
            .filter { it.type == "text" && it.text != null }
            .joinToString(separator = "") { it.text!! }
        if (text.isBlank()) {
            throw GeminiInteractionsException("Gemini model output text is missing")
        }
        val searchQueries = interaction.steps.orEmpty()
            .filter { it.type == "google_search_call" }
            .flatMap { it.arguments?.queries.orEmpty() }
        val groundingSources = finalModelOutput?.content.orEmpty()
            .filter { it.type == "text" }
            .flatMap { it.annotations.orEmpty() }
            .filter { it.type == "url_citation" && !it.url.isNullOrBlank() }
            .map { GeminiGroundingSource(url = it.url!!, title = it.title) }
        return GeminiInteractionResult(
            interactionId = interactionId,
            outputText = text,
            searchQueries = searchQueries,
            groundingSources = groundingSources,
        )
    }

    private fun requestBody(request: GeminiInteractionRequest): JsonObject = buildJsonObject {
        put("model", config.model)
        put("input", request.input)
        put("response_format", request.responseFormat)
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    addJsonObject { put("type", tool.apiType) }
                }
            }
        }
        put("store", request.store)
    }

    private fun httpFailure(response: HttpResponse): Exception {
        val retryAfter = response.headers[HttpHeaders.RetryAfter]?.let(::parseRetryAfter)
        return if (response.status.value == 429 || response.status.value in 500..599) {
            RetryableGeminiFailure(response.status.value, retryAfter)
        } else {
            GeminiInteractionsException("Gemini HTTP ${response.status.value}")
        }
    }

    private fun parseRetryAfter(value: String): Duration? =
        value.toLongOrNull()?.coerceAtLeast(0)?.seconds
            ?: runCatching {
                val target = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
                (target.toEpochMilli() - now().toEpochMilliseconds()).coerceAtLeast(0).milliseconds
            }.getOrNull()

    private fun isRetryable(exception: Exception): Boolean =
        exception is RetryableGeminiFailure ||
            // HTTP レスポンスへ到達していない I/O 障害を恒久失敗にすると、一時的なネットワーク断から回復できないため再試行する。
            exception is IOException ||
            exception is HttpRequestTimeoutException ||
            exception is ConnectTimeoutException ||
            exception is SocketTimeoutException

    private fun exponentialBackoff(attempt: Int): Duration =
        (1000L * (1L shl attempt) + Random.nextLong(0, 250)).milliseconds

    private class RetryableGeminiFailure(
        val status: Int,
        val retryAfter: Duration?,
    ) : RuntimeException("Gemini HTTP $status")
}
