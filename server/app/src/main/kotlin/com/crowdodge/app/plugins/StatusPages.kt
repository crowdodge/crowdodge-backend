package com.crowdodge.app.plugins

import com.crowdodge.shared.infra.web.Problem
import com.crowdodge.shared.infra.web.respondProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.plugins.statuspages.StatusPages
import kotlinx.coroutines.CancellationException

/**
 * 想定外/インフラ障害の例外をここで集約し、Problem(RFC9457) に変換する（§10.1/§10.3）。
 * 想定内のドメイン失敗は各 BC の presentation 層で Either の左 → Problem に変換する。
 */
fun Application.configureStatusPages() {
    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // クライアント切断などのコルーチンキャンセルは握りつぶさず再送出する
            // （500 として誤計上せず、キャンセル伝播を妨げないため）。
            if (cause is CancellationException) throw cause
            call.application.log.error("未処理の例外", cause)
            call.respondProblem(
                Problem(
                    status = HttpStatusCode.InternalServerError.value,
                    code = "INTERNAL_ERROR",
                    title = "Internal Server Error",
                    detail = "予期しないエラーが発生しました",
                )
            )
        }
    }
}
