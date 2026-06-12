package com.crowdodge.shared.infra.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.datetime.timestamp
import kotlin.time.Instant

/**
 * `created_at` / `updated_at`（timestamp, NOT NULL）を持つ抽象基底テーブル。
 * 大半のテーブルが継承する。値はドメインが発行するため DB デフォルトは付けない。
 */
abstract class TimestampedTable(name: String) : Table(name) {
    val createdAt: Column<Instant> = timestamp("created_at")
    val updatedAt: Column<Instant> = timestamp("updated_at")
}
