package com.crowdodge.congestion.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.instantTimestampWithTimeZone

/** 予定ごとの混雑予測を保持するテーブル。 */
object EventCongestionForecastsTable : TimestampedTable("event_congestion_forecasts") {
    val eventCongestionForecastUuid = uuid("event_congestion_forecast_uuid")
    val eventUuid = uuid("event_uuid")
    val generationInputHash = varchar("generation_input_hash", GENERATION_INPUT_HASH_LENGTH)
    val generatedAt = instantTimestampWithTimeZone("generated_at")

    override val primaryKey: PrimaryKey
        get() = PrimaryKey(eventCongestionForecastUuid)

    init {
        uniqueIndex(eventUuid)
        index(false, generationInputHash, generatedAt)
    }
}

private const val GENERATION_INPUT_HASH_LENGTH = 64
