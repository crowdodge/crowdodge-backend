package com.crowdodge.congestion.application.port

import com.crowdodge.congestion.domain.model.EventCongestionForecastUuid
import com.crowdodge.congestion.domain.model.EventUuid
import kotlin.time.Instant

/** 混雑予測の生成と再利用に必要な情報を読み取る。 */
interface CongestionGenerationReadModel {
    /** 指定した予定の生成候補を予定 UUID ごとに返す。 */
    suspend fun findAll(
        eventUuids: Set<EventUuid>,
    ): Map<EventUuid, CongestionGenerationCandidate>
}

/** 現在の生成元と保存済み予測をまとめた候補。 */
data class CongestionGenerationCandidate(
    val source: CongestionGenerationSource,
    val savedForecast: SavedForecast?,
)

/** 再利用判定に使う保存済み混雑予測。 */
data class SavedForecast(
    val forecastUuid: EventCongestionForecastUuid,
    val generationInputHash: String,
    val generatedAt: Instant,
    val periods: List<SavedCongestionPeriod>,
)

/** 保存層から読み取った混雑期間。 */
data class SavedCongestionPeriod(
    val start: Instant,
    val end: Instant,
    val area: String,
    val description: String,
)
