package com.crowdodge.shared.kernel

/**
 * readiness（処理可能性）確認ポート。
 * 依存リソース（DB 等）への到達性を確認する。infrastructure が実装し、app が readiness
 * エンドポイントから呼ぶ（liveness の `/health` とは分離する）。
 *
 * TransactionRunner と同じく技術的ポートであり、DIP で infrastructure 実装を逆転する
 * （app/presentation は Exposed/R2DBC を import しない）。
 */
interface ReadinessProbe {
    /**
     * 依存リソースへ到達可能なら true。
     * 不通・タイムアウト等の失敗は例外を投げず false を返す（readiness は HTTP 503 で表現する）。
     */
    suspend fun isReady(): Boolean
}
