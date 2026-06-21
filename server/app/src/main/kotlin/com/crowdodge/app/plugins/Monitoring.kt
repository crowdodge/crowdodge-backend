package com.crowdodge.app.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callIdMdc
import io.ktor.server.plugins.calllogging.CallLogging
import org.slf4j.event.Level
import kotlin.uuid.Uuid

/** クライアント提供 callId の最大長（ログ MDC に載るため上限を設ける）。 */
private const val MAX_CALL_ID_LENGTH = 128

/**
 * 構造化ログ + リクエストID/UserUuid の MDC（§13）。
 * リクエストごとに callId を採番し MDC に載せる。
 */
fun Application.configureMonitoring() {
    install(CallId) {
        header(HttpHeaders.XRequestId)
        generate { Uuid.random().toString() }
        // クライアント提供の callId はログ(MDC)に載るため、英数とハイフンに制限（ログインジェクション対策）。
        verify { id ->
            id.isNotEmpty() && id.length <= MAX_CALL_ID_LENGTH && id.all { it.isLetterOrDigit() || it == '-' }
        }
    }
    install(CallLogging) {
        level = Level.INFO
        callIdMdc("callId")
    }
}
