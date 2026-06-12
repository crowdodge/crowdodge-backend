package com.crowdodge.shared.infra.db

import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.ColumnType
import org.jetbrains.exposed.v1.core.Table
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * PostgreSQL `interval` 列を [kotlin.time.Duration] にマップするカスタム型。
 */
class IntervalColumnType : ColumnType<Duration>() {
    override fun sqlType(): String = "interval"

    // PostgreSQL は "<n> microseconds" 形式の interval リテラルを解釈する。
    override fun notNullValueToDB(value: Duration): Any = "${value.inWholeMicroseconds} microseconds"

    override fun valueFromDB(value: Any): Duration =
        when (value) {
            is Duration -> value
            is Number -> value.toLong().microseconds
            else -> error("interval 値を Duration に変換できません: ${value::class}")
        }
}

/** `interval` 列（[kotlin.time.Duration]）を登録する（§5.2）。 */
fun Table.interval(name: String): Column<Duration> = registerColumn(name, IntervalColumnType())
