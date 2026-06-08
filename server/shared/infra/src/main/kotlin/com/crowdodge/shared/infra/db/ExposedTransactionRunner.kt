package com.crowdodge.shared.infra.db

import com.crowdodge.shared.kernel.TransactionRunner
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

/**
 * [TransactionRunner] の Exposed(R2DBC) 実装（§11）。
 * ユースケースが開いたトランザクションにリポジトリが参加できるよう、
 * トランザクションはコルーチンコンテキストに束縛される。
 */
class ExposedTransactionRunner(
    private val db: R2dbcDatabase,
) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        suspendTransaction(db = db) { block() }

    override suspend fun <T> readOnly(block: suspend () -> T): T =
        suspendTransaction(db = db, readOnly = true) { block() }
}
