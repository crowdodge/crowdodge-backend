package com.crowdodge.congestion.domain.repository

import com.crowdodge.congestion.domain.model.EventCongestionForecast

/** 混雑予測集約を永続化するリポジトリ。 */
interface EventCongestionForecastRepository {
    /** 現在のトランザクションで予定の混雑予測を置換する。 */
    suspend fun replace(forecast: EventCongestionForecast)
}
