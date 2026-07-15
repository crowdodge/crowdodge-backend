package com.crowdodge.app.notification

import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.crowdodge.congestion.application.port.CongestionDestination
import com.crowdodge.congestion.application.port.CongestionForecastGenerator
import com.crowdodge.congestion.application.port.CongestionGenerationCandidate
import com.crowdodge.congestion.application.port.CongestionGenerationReadModel
import com.crowdodge.congestion.application.port.CongestionGenerationSource
import com.crowdodge.congestion.application.port.CongestionRoute
import com.crowdodge.congestion.application.service.GenerateCongestionInfoUseCase
import com.crowdodge.congestion.domain.error.CongestionError
import com.crowdodge.congestion.domain.model.CongestionPeriod.Companion.congestionPeriod
import com.crowdodge.congestion.domain.model.EventCongestionForecast
import com.crowdodge.congestion.domain.repository.EventCongestionForecastRepository
import com.crowdodge.congestion.infrastructure.hash.Sha256GenerationInputHashCalculator
import com.crowdodge.notification.application.port.CongestionInfoResult
import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.shared.kernel.TransactionRunner
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid
import com.crowdodge.congestion.domain.model.EventUuid as CongestionEventUuid
import com.crowdodge.notification.domain.model.EventUuid as NotificationEventUuid

private val ADAPTER_NOW = Instant.parse("2026-08-08T00:00:00Z")

class CongestionInfoAdapterTest : FunSpec({
    test("通知BCのUUIDとDTOを混雑BCとの境界で変換する") {
        val uuid = Uuid.random()
        val congestionUuid = CongestionEventUuid(uuid)
        val source = CongestionGenerationSource(
            eventUuid = congestionUuid,
            start = Instant.parse("2026-08-08T09:00:00Z"),
            end = Instant.parse("2026-08-08T11:00:00Z"),
            isAllDay = false,
            destination = CongestionDestination("会場", 35.6, 139.7),
            outboundRoute = CongestionRoute(emptyList()),
            travelDuration = 1.hours,
        )
        val candidate = CongestionGenerationCandidate(source, savedForecast = null)
        val readModel = FakeGenerationReadModel(candidate)
        val useCase = GenerateCongestionInfoUseCase(
            readModel = readModel,
            generator = CongestionGeneratorStub,
            forecasts = ForecastRepositoryStub,
            transactions = ImmediateAppTransactionRunner,
            clock = FixedAppClock,
            maxConcurrency = 1,
            hashCalculator = Sha256GenerationInputHashCalculator(),
        )

        val result = CongestionInfoAdapter(useCase).findAll(listOf(NotificationEventUuid(uuid)))

        result.keys shouldBe setOf(NotificationEventUuid(uuid))
        result[NotificationEventUuid(uuid)] shouldBe CongestionInfoResult.Success(
            com.crowdodge.notification.application.port.CongestionInfo("17〜18時に混雑予測あり"),
        )
    }

    test("混雑BCの一時失敗を通知BCの再試行可能な失敗へ変換する") {
        val uuid = Uuid.random()
        val congestionUuid = CongestionEventUuid(uuid)
        val candidate = CongestionGenerationCandidate(
            CongestionGenerationSource(
                eventUuid = congestionUuid,
                start = Instant.parse("2026-08-08T09:00:00Z"),
                end = Instant.parse("2026-08-08T11:00:00Z"),
                isAllDay = false,
                destination = CongestionDestination("会場", 35.6, 139.7),
                outboundRoute = CongestionRoute(emptyList()),
                travelDuration = 1.hours,
            ),
            savedForecast = null,
        )
        val useCase = GenerateCongestionInfoUseCase(
            readModel = FakeGenerationReadModel(candidate),
            generator = CongestionForecastGenerator {
                CongestionError.ExternalError.GenerationTemporarilyUnavailable.left()
            },
            forecasts = ForecastRepositoryStub,
            transactions = ImmediateAppTransactionRunner,
            clock = FixedAppClock,
            maxConcurrency = 1,
            hashCalculator = Sha256GenerationInputHashCalculator(),
        )

        CongestionInfoAdapter(useCase).findAll(listOf(NotificationEventUuid(uuid))) shouldBe mapOf(
            NotificationEventUuid(uuid) to CongestionInfoResult.Failure(
                NotificationError.CongestionInfoError.TemporarilyUnavailable,
            ),
        )
    }

    test("混雑BCの生成元なしを通知BCの恒久失敗へ変換する") {
        val uuid = Uuid.random()
        val useCase = GenerateCongestionInfoUseCase(
            readModel = FakeGenerationReadModel(null),
            generator = CongestionGeneratorStub,
            forecasts = ForecastRepositoryStub,
            transactions = ImmediateAppTransactionRunner,
            clock = FixedAppClock,
            maxConcurrency = 1,
            hashCalculator = Sha256GenerationInputHashCalculator(),
        )

        CongestionInfoAdapter(useCase).findAll(listOf(NotificationEventUuid(uuid))) shouldBe mapOf(
            NotificationEventUuid(uuid) to CongestionInfoResult.Failure(
                NotificationError.CongestionInfoError.PermanentlyUnavailable,
            ),
        )
    }

    test("重複した通知BCのUUIDを混雑BCへ一意な集合として渡す") {
        val uuid = Uuid.random()
        val readModel = FakeGenerationReadModel(null)
        val useCase = GenerateCongestionInfoUseCase(
            readModel = readModel,
            generator = CongestionGeneratorStub,
            forecasts = ForecastRepositoryStub,
            transactions = ImmediateAppTransactionRunner,
            clock = FixedAppClock,
            maxConcurrency = 1,
            hashCalculator = Sha256GenerationInputHashCalculator(),
        )

        CongestionInfoAdapter(useCase).findAll(
            listOf(NotificationEventUuid(uuid), NotificationEventUuid(uuid)),
        )

        readModel.requestedEventUuids shouldBe setOf(CongestionEventUuid(uuid))
    }
})

private class FakeGenerationReadModel(
    private val candidate: CongestionGenerationCandidate?,
) : CongestionGenerationReadModel {
    var requestedEventUuids = emptySet<CongestionEventUuid>()

    override suspend fun findAll(
        eventUuids: Set<CongestionEventUuid>,
    ): Map<CongestionEventUuid, CongestionGenerationCandidate> {
        requestedEventUuids = eventUuids
        return candidate?.let { mapOf(it.source.eventUuid to it) } ?: emptyMap()
    }
}

private object CongestionGeneratorStub : CongestionForecastGenerator {
    override suspend fun generate(source: CongestionGenerationSource) =
        listOf(
            either {
                congestionPeriod(
                    start = Instant.parse("2026-08-08T08:00:00Z"),
                    end = Instant.parse("2026-08-08T09:00:00Z"),
                    area = "会場",
                    description = "混雑",
                )
            }.getOrNull()!!,
        ).right()
}

private object ForecastRepositoryStub : EventCongestionForecastRepository {
    override suspend fun replace(forecast: EventCongestionForecast) = Unit
}

private object ImmediateAppTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private object FixedAppClock : Clock {
    override fun now(): Instant = ADAPTER_NOW
}
