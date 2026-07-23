package com.crowdodge.congestion.application.port

import arrow.core.Either
import com.crowdodge.congestion.domain.error.CongestionError
import com.crowdodge.congestion.domain.model.CongestionPeriod

/** 予定と経路から混雑予測を生成する。 */
fun interface CongestionForecastGenerator {
    /** 指定した生成元に影響する混雑期間を返す。 */
    suspend fun generate(
        source: CongestionGenerationSource,
    ): Either<CongestionError.GenerationError, List<CongestionPeriod>>
}
