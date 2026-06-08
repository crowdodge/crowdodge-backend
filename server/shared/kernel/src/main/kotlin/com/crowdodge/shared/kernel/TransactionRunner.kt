package com.crowdodge.shared.kernel

/**
 * トランザクション境界ポート（§11）。
 * application がこのポートに依存し、infrastructure が exposed-r2dbc の
 * `suspendTransaction` で実装する（DIP による依存逆転。application は Exposed を import しない）。
 *
 * 1 ユースケース = 1 トランザクション。原則 1 集約の変更を同一トランザクションにまとめる。
 *
 * 書き込み（[inTransaction]）と読み取り専用（[readOnly]）を分ける。
 * - 書き込み: command 系ユースケース。集約変更をまとめる。
 * - 読み取り専用: query 系ユースケース。読み取り専用フラグで明示し、
 *   将来のリードレプリカ振り分けや最適化の余地を残す。
 *
 * 型パラメータ付きメソッドのため `fun interface` にはできない（SAM 変換非対応）。
 */
interface TransactionRunner {
    /** 書き込みトランザクション境界。 */
    suspend fun <T> inTransaction(block: suspend () -> T): T

    /** 読み取り専用トランザクション境界。 */
    suspend fun <T> readOnly(block: suspend () -> T): T
}
