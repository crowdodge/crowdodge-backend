package com.crowdodge.shared.infra.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Table
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * `created_at` / `updated_at`（timestamptz, NOT NULL）を持つ抽象基底テーブル。
 * 大半のテーブルが継承する。値はドメインが発行するため DB デフォルトは付けない。
 */
abstract class TimestampedTable(name: String) : Table(name) {
    val createdAt: Column<Instant> = instantTimestampWithTimeZone("created_at").clientDefault { Clock.System.now() }
    val updatedAt: Column<Instant> = instantTimestampWithTimeZone("updated_at").clientDefault { Clock.System.now() }
}
