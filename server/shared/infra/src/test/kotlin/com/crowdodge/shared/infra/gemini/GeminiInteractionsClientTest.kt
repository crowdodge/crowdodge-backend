package com.crowdodge.shared.infra.gemini

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

class GeminiInteractionsClientTest : FunSpec({

    test("Interaction IDと最終テキストとGoogle Searchの検索語を応答順に返す") {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val httpClient = mockHttpClient(
            responses = mutableListOf(
                HttpStatusCode.OK to interactionResponse(
                    InteractionResponseFixture(
                        modelText = "{\"result\":true}",
                        searchQueries = listOf("会場 2026年8月1日 イベント", "経由駅 花火大会"),
                        earlierModelText = "{\"result\":false}",
                        annotations = listOf(
                            TestAnnotation("url_citation", "https://example.com/final", "公式情報"),
                            TestAnnotation("other", "https://example.com/ignored", "対象外"),
                            TestAnnotation("url_citation", " ", "空URL"),
                            TestAnnotation("url_citation", "https://example.com/untitled", null),
                        ),
                        earlierAnnotations = listOf(
                            TestAnnotation("url_citation", "https://example.com/earlier", "以前の出力"),
                        ),
                    ),
                ),
            ),
            requests = requests,
        )
        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            config = GeminiInteractionsConfig(
                apiBaseUrl = "https://example.test/",
                apiKey = "secret",
            ),
        )

        val result = client.interact(
            GeminiInteractionRequest(
                input = "input",
                responseFormat = buildJsonObject { put("type", "text") },
            ),
        )

        result shouldBe GeminiInteractionResult(
            interactionId = "interaction-1",
            outputText = "{\"result\":true}",
            searchQueries = listOf("会場 2026年8月1日 イベント", "経由駅 花火大会"),
            groundingSources = listOf(
                GeminiGroundingSource("https://example.com/final", "公式情報"),
                GeminiGroundingSource("https://example.com/untitled", null),
            ),
        )
        requests.single().method shouldBe HttpMethod.Post
        requests.single().url.toString() shouldBe "https://example.test/v1/interactions"
        requests.single().headers["x-goog-api-key"] shouldBe "secret"
        requests.single().body.toByteArray().decodeToString() shouldContain "\"model\":\"gemini-3.5-flash\""
        requests.single().body.toByteArray().decodeToString() shouldContain "\"input\":\"input\""
        requests.single().body.toByteArray().decodeToString() shouldContain "\"response_format\""
        httpClient.close()
    }

    test("Google Search指定時はtoolとstoreをrequest bodyへ含める") {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val httpClient = mockHttpClient(
            responses = mutableListOf(HttpStatusCode.OK to interactionResponse("{\"result\":true}")),
            requests = requests,
        )
        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            config = GeminiInteractionsConfig(
                apiBaseUrl = "https://example.test",
                apiKey = "secret",
            ),
        )

        client.interact(
            GeminiInteractionRequest(
                input = "input",
                responseFormat = buildJsonObject { put("type", "text") },
                tools = setOf(GeminiInteractionTool.GoogleSearch),
            ),
        )

        val requestBody = requests.single().body.toByteArray().decodeToString()
        requestBody shouldContain "\"tools\":[{\"type\":\"google_search\"}]"
        requestBody shouldContain "\"store\":false"
        httpClient.close()
    }

    test("tools未指定時はrequest bodyからtoolsを省略する") {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val httpClient = mockHttpClient(
            responses = mutableListOf(HttpStatusCode.OK to interactionResponse("{\"result\":true}")),
            requests = requests,
        )
        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            config = GeminiInteractionsConfig(
                apiBaseUrl = "https://example.test",
                apiKey = "secret",
            ),
        )

        client.interact(
            GeminiInteractionRequest(
                input = "input",
                responseFormat = buildJsonObject { put("type", "text") },
            ),
        )

        val requestBody = requests.single().body.toByteArray().decodeToString()
        requestBody shouldNotContain "\"tools\""
        requestBody shouldContain "\"store\":false"
        httpClient.close()
    }

    test("Interaction ID欠損を技術例外として拒否する") {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val httpClient = mockHttpClient(
            responses = mutableListOf(
                HttpStatusCode.OK to interactionResponse(
                    InteractionResponseFixture(modelText = "{\"result\":true}", interactionId = null),
                ),
            ),
            requests = requests,
        )
        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            config = GeminiInteractionsConfig(
                apiBaseUrl = "https://example.test",
                apiKey = "secret",
            ),
        )

        shouldThrow<GeminiInteractionsException> {
            client.interact(
                GeminiInteractionRequest(
                    input = "input",
                    responseFormat = buildJsonObject { put("type", "text") },
                ),
            )
        }

        requests shouldHaveSize 1
        httpClient.close()
    }

    test("429の後は最大1回だけ再試行する") {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val delays = mutableListOf<kotlin.time.Duration>()
        val httpClient = mockHttpClient(
            responses = mutableListOf(
                HttpStatusCode.TooManyRequests to "{}",
                HttpStatusCode.OK to interactionResponse("{\"result\":true}"),
            ),
            requests = requests,
        )
        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            config = GeminiInteractionsConfig(
                apiBaseUrl = "https://example.test",
                apiKey = "secret",
                maxAttempts = 2,
            ),
            sleeper = { delays += it },
        )

        client.interact(
            GeminiInteractionRequest(
                input = "input",
                responseFormat = buildJsonObject { put("type", "text") },
            ),
        )

        requests shouldHaveSize 2
        delays shouldHaveSize 1
        httpClient.close()
    }

    test("認証などの4xxは再試行しない") {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val httpClient = mockHttpClient(
            responses = mutableListOf(HttpStatusCode.Unauthorized to "{}"),
            requests = requests,
        )
        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            config = GeminiInteractionsConfig(
                apiBaseUrl = "https://example.test",
                apiKey = "secret",
            ),
        )

        val failure = shouldThrow<GeminiInteractionsException> {
            client.interact(
                GeminiInteractionRequest(
                    input = "input",
                    responseFormat = buildJsonObject { put("type", "text") },
                ),
            )
        }

        failure.isRetryable shouldBe false
        requests shouldHaveSize 1
        httpClient.close()
    }

    test("retry上限に達した一時障害は共通の技術例外へ変換する") {
        val requests = mutableListOf<io.ktor.client.request.HttpRequestData>()
        val httpClient = mockHttpClient(
            responses = mutableListOf(
                HttpStatusCode.InternalServerError to "{}",
                HttpStatusCode.InternalServerError to "{}",
            ),
            requests = requests,
        )
        val client = GeminiInteractionsClient(
            httpClient = httpClient,
            config = GeminiInteractionsConfig(
                apiBaseUrl = "https://example.test",
                apiKey = "secret",
            ),
        )

        val failure = shouldThrow<GeminiInteractionsException> {
            client.interact(
                GeminiInteractionRequest(
                    input = "input",
                    responseFormat = buildJsonObject { put("type", "text") },
                ),
            )
        }

        failure.isRetryable shouldBe true
        requests shouldHaveSize 2
        httpClient.close()
    }
})

private fun mockHttpClient(
    responses: MutableList<Pair<HttpStatusCode, String>>,
    requests: MutableList<io.ktor.client.request.HttpRequestData>,
): HttpClient = HttpClient(MockEngine) {
    expectSuccess = false
    engine {
        addHandler { request ->
            requests += request
            val (status, body) = responses.removeAt(0)
            respond(
                content = body,
                status = status,
                headers = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()),
            )
        }
    }
}

private data class InteractionResponseFixture(
    val modelText: String,
    val searchQueries: List<String> = emptyList(),
    val interactionId: String? = "interaction-1",
    val earlierModelText: String? = null,
    val annotations: List<TestAnnotation> = emptyList(),
    val earlierAnnotations: List<TestAnnotation> = emptyList(),
)

private fun interactionResponse(modelText: String): String =
    interactionResponse(InteractionResponseFixture(modelText = modelText))

private fun interactionResponse(fixture: InteractionResponseFixture): String = buildJsonObject {
    fixture.interactionId?.let { put("id", it) }
    put("status", "completed")
    putJsonArray("steps") {
        fixture.earlierModelText?.let { add(modelOutputStep(it, fixture.earlierAnnotations)) }
        fixture.searchQueries.forEach { query ->
            add(
                buildJsonObject {
                    put("type", "google_search_call")
                    put(
                        "arguments",
                        buildJsonObject {
                            putJsonArray("queries") {
                                add(JsonPrimitive(query))
                            }
                        },
                    )
                },
            )
        }
        add(modelOutputStep(fixture.modelText, fixture.annotations))
    }
}.toString()

private fun modelOutputStep(
    modelText: String,
    annotations: List<TestAnnotation> = emptyList(),
) = buildJsonObject {
    put("type", "model_output")
    putJsonArray("content") {
        add(
            buildJsonObject {
                put("type", "text")
                put("text", modelText)
                putJsonArray("annotations") {
                    annotations.forEach { annotation ->
                        add(
                            buildJsonObject {
                                put("type", annotation.type)
                                annotation.url?.let { put("url", it) }
                                annotation.title?.let { put("title", it) }
                            },
                        )
                    }
                }
            },
        )
    }
}

private data class TestAnnotation(
    val type: String,
    val url: String?,
    val title: String?,
)

private fun io.ktor.http.content.OutgoingContent.toByteArray(): ByteArray =
    when (this) {
        is io.ktor.http.content.OutgoingContent.ByteArrayContent -> bytes()
        else -> byteArrayOf()
    }
