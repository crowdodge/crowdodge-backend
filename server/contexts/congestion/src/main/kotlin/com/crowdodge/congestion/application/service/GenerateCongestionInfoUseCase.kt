@file:Suppress("LongParameterList", "ReturnCount")

package com.crowdodge.congestion.application.service

import arrow.core.raise.either
import com.crowdodge.congestion.application.port.CongestionForecastGenerator
import com.crowdodge.congestion.application.port.CongestionGenerationCandidate
import com.crowdodge.congestion.application.port.CongestionGenerationReadModel
import com.crowdodge.congestion.application.port.CongestionGenerationSource
import com.crowdodge.congestion.application.port.GenerationInputHashCalculator
import com.crowdodge.congestion.application.port.SavedCongestionPeriod
import com.crowdodge.congestion.domain.error.CongestionError
import com.crowdodge.congestion.domain.model.CongestionPeriod
import com.crowdodge.congestion.domain.model.CongestionPeriod.Companion.congestionPeriod
import com.crowdodge.congestion.domain.model.EventCongestionForecast
import com.crowdodge.congestion.domain.model.EventCongestionForecast.Companion.forecast
import com.crowdodge.congestion.domain.model.EventUuid
import com.crowdodge.congestion.domain.repository.EventCongestionForecastRepository
import com.crowdodge.congestion.domain.service.CongestionSummaryFormatter
import com.crowdodge.shared.kernel.TransactionRunner
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/** 通知本文へ追加する混雑概要。 */
data class CongestionSummary(val description: String)

/** 予定ごとの混雑情報取得結果。 */
sealed interface CongestionInfoResult {
    /** 混雑情報を取得できた結果。混雑なしの場合は [summary] が null になる。 */
    data class Success(val summary: CongestionSummary?) : CongestionInfoResult

    /** 混雑情報を取得できなかった結果。 */
    data class Failure(val error: CongestionError.GenerationError) : CongestionInfoResult
}

/** 保存済み予測の再利用と新規生成を調停する。 */
class GenerateCongestionInfoUseCase(
    private val readModel: CongestionGenerationReadModel,
    private val generator: CongestionForecastGenerator,
    private val forecasts: EventCongestionForecastRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val maxConcurrency: Int,
    private val hashCalculator: GenerationInputHashCalculator,
    private val summaryFormatter: CongestionSummaryFormatter = CongestionSummaryFormatter(),
) {
    init {
        require(maxConcurrency > 0) { "maxConcurrency must be greater than zero" }
    }

    /** 指定したすべての予定について混雑情報の取得結果を返す。 */
    suspend fun execute(eventUuids: Set<EventUuid>): Map<EventUuid, CongestionInfoResult> {
        if (eventUuids.isEmpty()) return emptyMap()

        val candidatesBeforeGeneration = readModel.findAll(eventUuids)
        val inputHashesBeforeGeneration = candidatesBeforeGeneration.mapValues { (_, candidate) ->
            candidate.inputHash()
        }
        val resultsByEvent = eventUuids.associateWith<EventUuid, CongestionInfoResult> {
            CongestionInfoResult.Failure(CongestionError.GenerationError.GenerationSourceNotFound)
        }.toMutableMap()
        val candidatesRequiringGeneration = reuseSavedForecasts(
            candidates = candidatesBeforeGeneration,
            inputHashes = inputHashesBeforeGeneration,
            now = clock.now(),
            resultsByEvent = resultsByEvent,
        )

        if (candidatesRequiringGeneration.isEmpty()) return resultsByEvent

        val generationResults = generateForecasts(
            candidatesRequiringGeneration,
            inputHashesBeforeGeneration,
        )
        recordGenerationFailures(generationResults, resultsByEvent)

        val generatedForecasts = generationResults.mapNotNull { (eventUuid, generationResult) ->
            (generationResult as? GeneratedResult.Success)?.let { eventUuid to it }
        }.toMap()
        if (generatedForecasts.isEmpty()) return resultsByEvent

        verifyAndPersistGeneratedForecasts(generatedForecasts, resultsByEvent)
        return resultsByEvent
    }

    /** 再利用できる保存済み予測を結果へ反映し、生成が必要な候補を返す。 */
    private fun reuseSavedForecasts(
        candidates: Map<EventUuid, CongestionGenerationCandidate>,
        inputHashes: Map<EventUuid, String>,
        now: Instant,
        resultsByEvent: MutableMap<EventUuid, CongestionInfoResult>,
    ): Map<EventUuid, CongestionGenerationCandidate> = candidates.filterNot { (eventUuid, candidate) ->
        val inputHash = inputHashes.getValue(eventUuid)
        val saved = candidate.savedForecast
        if (saved != null &&
            saved.generationInputHash == inputHash &&
            saved.generatedAt >= now - REUSE_WINDOW
        ) {
            recordSuccess(resultsByEvent, eventUuid, candidate.source, saved.periods.map(::restoreSavedPeriod))
            true
        } else {
            false
        }
    }

    /** 生成失敗を予定ごとの結果へ反映する。 */
    private fun recordGenerationFailures(
        generationResults: Map<EventUuid, GeneratedResult>,
        resultsByEvent: MutableMap<EventUuid, CongestionInfoResult>,
    ) {
        generationResults.filterValues { it is GeneratedResult.Failure }.forEach { (eventUuid, generationResult) ->
            recordFailure(resultsByEvent, eventUuid, (generationResult as GeneratedResult.Failure).error)
        }
    }

    /** 生成後も入力が変わっていない予測を保存する。 */
    private suspend fun verifyAndPersistGeneratedForecasts(
        generatedForecasts: Map<EventUuid, GeneratedResult.Success>,
        resultsByEvent: MutableMap<EventUuid, CongestionInfoResult>,
    ) {
        val candidatesAfterGeneration = readModel.findAll(generatedForecasts.keys)
        val verifiedForecasts = generatedForecasts.mapNotNull { (eventUuid, generatedForecast) ->
            val currentCandidate = candidatesAfterGeneration[eventUuid]
            if (currentCandidate == null) {
                recordFailure(
                    resultsByEvent,
                    eventUuid,
                    CongestionError.GenerationError.GenerationSourceNotFound,
                )
                return@mapNotNull null
            }
            if (currentCandidate.inputHash() != generatedForecast.inputHash) {
                recordFailure(resultsByEvent, eventUuid, CongestionError.GenerationError.GenerationInputChanged)
                return@mapNotNull null
            }

            val savedAt = clock.now()
            val forecast = either {
                forecast(
                    eventUuid = eventUuid,
                    generationInputHash = generatedForecast.inputHash,
                    generatedAt = savedAt,
                    periods = generatedForecast.periods,
                )
            }.fold(
                ifLeft = { validationError ->
                    error("検証済み混雑予測の集約構築に失敗しました: ${validationError.code}")
                },
                ifRight = { it },
            )
            VerifiedForecast(
                eventUuid = eventUuid,
                forecast = forecast,
                source = currentCandidate.source,
                periods = generatedForecast.periods,
            )
        }

        if (verifiedForecasts.isEmpty()) return

        verifiedForecasts.forEach { verified ->
            transactions.inTransaction { forecasts.replace(verified.forecast) }
            recordSuccess(resultsByEvent, verified.eventUuid, verified.source, verified.periods)
        }
    }

    /** 最大同時実行数を守りながら予測を並列生成する。 */
    private suspend fun generateForecasts(
        candidates: Map<EventUuid, CongestionGenerationCandidate>,
        inputHashes: Map<EventUuid, String>,
    ): Map<EventUuid, GeneratedResult> = coroutineScope {
        val semaphore = Semaphore(maxConcurrency)
        candidates.map { (eventUuid, candidate) ->
            async {
                val generated = semaphore.withPermit { generator.generate(candidate.source) }
                eventUuid to generated.fold(
                    ifLeft = { GeneratedResult.Failure(it) },
                    ifRight = { periods ->
                        GeneratedResult.Success(
                            inputHash = inputHashes.getValue(eventUuid),
                            periods = periods,
                        )
                    },
                )
            }
        }.awaitAll().toMap()
    }

    /** 混雑期間を通知用の成功結果へ変換する。 */
    private fun recordSuccess(
        resultsByEvent: MutableMap<EventUuid, CongestionInfoResult>,
        eventUuid: EventUuid,
        source: CongestionGenerationSource,
        periods: List<CongestionPeriod>,
    ) {
        val summary = summaryFormatter.format(source.start, source.end, periods)?.let(::CongestionSummary)
        resultsByEvent[eventUuid] = CongestionInfoResult.Success(summary)
    }

    /** 生成エラーを失敗結果へ変換する。 */
    private fun recordFailure(
        resultsByEvent: MutableMap<EventUuid, CongestionInfoResult>,
        eventUuid: EventUuid,
        error: CongestionError.GenerationError,
    ) {
        resultsByEvent[eventUuid] = CongestionInfoResult.Failure(error)
    }

    /** 候補の生成入力ハッシュを計算する。 */
    private fun CongestionGenerationCandidate.inputHash(): String =
        hashCalculator.calculate(source)

    /** 保存値を検証済みの混雑期間へ復元する。 */
    private fun restoreSavedPeriod(period: SavedCongestionPeriod): CongestionPeriod = either {
        congestionPeriod(period.start, period.end, period.area, period.description)
    }.fold(
        ifLeft = { throw IllegalStateException("保存済み混雑期間が不正です: ${it.code}") },
        ifRight = { it },
    )

    /** 保存条件を満たした予測と結果作成に必要な値。 */
    private data class VerifiedForecast(
        val eventUuid: EventUuid,
        val forecast: EventCongestionForecast,
        val source: CongestionGenerationSource,
        val periods: List<CongestionPeriod>,
    )

    /** 一件の予測生成結果。 */
    private sealed interface GeneratedResult {
        data class Success(
            val inputHash: String,
            val periods: List<CongestionPeriod>,
        ) : GeneratedResult

        data class Failure(val error: CongestionError.GenerationError) : GeneratedResult
    }

    private companion object {
        val REUSE_WINDOW = 7.days
    }
}
