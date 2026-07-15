package com.crowdodge.congestion.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.congestion.domain.error.CongestionError
import com.crowdodge.shared.kernel.EntityUuid
import kotlin.time.Instant
import kotlin.uuid.Uuid

/** 混雑 BC が参照する予定 UUID。 */
// event BC の値型を共有すると混雑 BC が event BC の実装に依存するため、境界内で定義する。
@JvmInline
value class EventUuid(override val value: Uuid) : EntityUuid

/** 保存済み混雑予測の UUID。 */
@JvmInline
value class EventCongestionForecastUuid(override val value: Uuid) : EntityUuid {
    companion object {
        /** 新しい混雑予測 UUID を採番する。 */
        fun new(): EventCongestionForecastUuid = EventCongestionForecastUuid(Uuid.random())
    }
}

/** 検証済みの生成入力ハッシュ。 */
@JvmInline
value class GenerationInputHash private constructor(val value: String) {
    companion object {
        /** 64桁の小文字16進文字列から生成入力ハッシュを作成する。 */
        fun Raise<CongestionError.ValidationError>.generationInputHash(value: String): GenerationInputHash {
            ensure(HASH_PATTERN.matches(value)) { CongestionError.ValidationError.InvalidGenerationInputHash }
            return GenerationInputHash(value)
        }

        private val HASH_PATTERN = Regex("[0-9a-f]{64}")
    }
}

/** 予測された混雑の期間・場所・説明。 */
@ConsistentCopyVisibility
data class CongestionPeriod private constructor(
    val start: Instant,
    val end: Instant,
    val area: String,
    val description: String,
) {
    companion object {
        /** 時刻範囲と空文字を検証して混雑期間を作成する。 */
        fun Raise<CongestionError.ValidationError>.congestionPeriod(
            start: Instant,
            end: Instant,
            area: String,
            description: String,
        ): CongestionPeriod {
            ensure(start < end) { CongestionError.ValidationError.InvalidCongestionPeriodRange }
            val normalizedArea = area.trim()
            ensure(normalizedArea.isNotEmpty()) { CongestionError.ValidationError.BlankCongestionArea }
            val normalizedDescription = description.trim()
            ensure(normalizedDescription.isNotEmpty()) { CongestionError.ValidationError.BlankCongestionDescription }
            return CongestionPeriod(start, end, normalizedArea, normalizedDescription)
        }
    }
}

/** 一つの予定に対する混雑予測集約。 */
@ConsistentCopyVisibility
data class EventCongestionForecast private constructor(
    val eventCongestionForecastUuid: EventCongestionForecastUuid,
    val eventUuid: EventUuid,
    val generationInputHash: GenerationInputHash,
    val generatedAt: Instant,
    val periods: List<CongestionPeriod>,
) {
    companion object {
        /** 予定と生成条件から最大3件の混雑期間を持つ予測を作成する。 */
        fun Raise<CongestionError.ValidationError>.forecast(
            eventUuid: EventUuid,
            generationInputHash: String,
            generatedAt: Instant,
            periods: List<CongestionPeriod>,
        ): EventCongestionForecast {
            ensure(periods.size <= MAX_PERIODS) { CongestionError.ValidationError.TooManyCongestionPeriods }
            return EventCongestionForecast(
                eventCongestionForecastUuid = EventCongestionForecastUuid.new(),
                eventUuid = eventUuid,
                generationInputHash = GenerationInputHash.run { generationInputHash(generationInputHash) },
                generatedAt = generatedAt,
                periods = periods,
            )
        }

        private const val MAX_PERIODS = 3
    }
}
