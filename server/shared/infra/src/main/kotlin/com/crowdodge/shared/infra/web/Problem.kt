package com.crowdodge.shared.infra.web

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** `application/problem+json` メディアタイプ（RFC 9457）。 */
val ProblemJson: ContentType = ContentType("application", "problem+json")

private val problemJsonCodec = Json { encodeDefaults = true }

/**
 * RFC 9457 Problem Details（§10.3）。
 * 各 BC の presentation 層が `Either` の左を `toProblem()` で変換し [respondProblem] で返す。
 * 集約の一括検証（NonEmptyList）は [violations] に詰める。
 */
@Serializable
data class Problem(
    val status: Int,
    val type: String,
    val title: String? = null,
    val detail: String? = null,
    val violations: List<Violation> = emptyList(),
) {
    init {
        require(status in 100..599) { "HTTP ステータスは 100..599 の範囲: $status" }
    }

    @Serializable
    data class Violation(val field: String, val message: String)
}

/**
 * Problem を `application/problem+json` として応答する（§10.3）。
 * ContentNegotiation に依存せず明示的にシリアライズするため、エラー応答時に
 * 再ネゴシエーション由来の二次例外（コンバータ未install やシリアライズ失敗）を避けられる。
 */
suspend fun ApplicationCall.respondProblem(problem: Problem) {
    respondText(
        text = problemJsonCodec.encodeToString(Problem.serializer(), problem),
        contentType = ProblemJson,
        status = HttpStatusCode.fromValue(problem.status),
    )
}
