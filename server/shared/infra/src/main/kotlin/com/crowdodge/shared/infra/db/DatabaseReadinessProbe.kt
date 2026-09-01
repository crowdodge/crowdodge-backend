package com.crowdodge.shared.infra.db

import com.crowdodge.shared.kernel.ReadinessProbe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * R2DBC 接続の到達性を `SELECT 1` で確認する [ReadinessProbe] 実装。
 *
 * R2DBC は遅延接続のため、このクエリで初めて実接続を張る（起動時には接続しない）。
 * ハングした DB でプローブが固まらないよう [timeout] で上限を設け、不通・タイムアウトは
 * 例外を投げず false を返す（呼び出し側が HTTP 503 に変換する）。
 */
class DatabaseReadinessProbe(
    private val db: R2dbcDatabase,
    private val timeout: Duration = DEFAULT_TIMEOUT,
) : ReadinessProbe {
    override suspend fun isReady(): Boolean =
        runCatching {
            withTimeoutOrNull(timeout) {
                suspendTransaction(db = db, readOnly = true) {
                    exec("SELECT 1")
                }
                true
            } ?: run {
                log.warn("readiness check timed out after {}", timeout)
                false
            }
        }.getOrElse { cause ->
            if (cause is CancellationException) throw cause
            log.warn("readiness check failed: {}", cause.message)
            false
        }

    private companion object {
        private val log = LoggerFactory.getLogger(DatabaseReadinessProbe::class.java)
        private val DEFAULT_TIMEOUT: Duration = 5.seconds
    }
}
