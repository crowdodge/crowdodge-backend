package com.crowdodge.congestion.application.service

import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.crowdodge.congestion.application.port.CongestionDestination
import com.crowdodge.congestion.application.port.CongestionForecastGenerator
import com.crowdodge.congestion.application.port.CongestionGenerationCandidate
import com.crowdodge.congestion.application.port.CongestionGenerationReadModel
import com.crowdodge.congestion.application.port.CongestionGenerationSource
import com.crowdodge.congestion.application.port.CongestionRoute
import com.crowdodge.congestion.application.port.GenerationInputHashCalculator
import com.crowdodge.congestion.application.port.SavedCongestionPeriod
import com.crowdodge.congestion.application.port.SavedForecast
import com.crowdodge.congestion.domain.error.CongestionError
import com.crowdodge.congestion.domain.model.CongestionPeriod.Companion.congestionPeriod
import com.crowdodge.congestion.domain.model.EventCongestionForecast
import com.crowdodge.congestion.domain.model.EventCongestionForecastUuid
import com.crowdodge.congestion.domain.model.EventUuid
import com.crowdodge.congestion.domain.repository.EventCongestionForecastRepository
import com.crowdodge.congestion.infrastructure.hash.Sha256GenerationInputHashCalculator
import com.crowdodge.shared.kernel.TransactionRunner
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

private val USE_CASE_NOW = Instant.parse("2026-08-08T00:00:00Z")

private object UseCaseClock : Clock {
    override fun now(): Instant = USE_CASE_NOW
}

private object ImmediateCongestionTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class RecordingCongestionTransactionRunner : TransactionRunner {
    var inTransactionCalls = 0

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        inTransactionCalls += 1
        return block()
    }

    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class RecordingGenerationReadModel(
    private val responses: ArrayDeque<Map<EventUuid, CongestionGenerationCandidate>>,
) : CongestionGenerationReadModel {
    val calls = mutableListOf<Set<EventUuid>>()

    override suspend fun findAll(eventUuids: Set<EventUuid>): Map<EventUuid, CongestionGenerationCandidate> {
        calls += eventUuids
        return responses.removeFirstOrNull() ?: emptyMap()
    }
}

private class RecordingForecastRepository(
    private val failure: Throwable? = null,
) : EventCongestionForecastRepository {
    val calls = mutableListOf<EventCongestionForecast>()

    override suspend fun replace(forecast: EventCongestionForecast) {
        failure?.let { throw it }
        calls += forecast
    }
}

private fun source(eventUuid: EventUuid, start: Instant = Instant.parse("2026-08-08T09:00:00Z")) =
    CongestionGenerationSource(
        eventUuid = eventUuid,
        start = start,
        end = start + 2.hours,
        isAllDay = false,
        destination = CongestionDestination("venue", 35.6, 139.7),
        outboundRoute = CongestionRoute(emptyList()),
        travelDuration = 1.hours,
    )

private fun candidate(
    eventUuid: EventUuid,
    savedForecast: SavedForecast? = null,
    start: Instant = Instant.parse("2026-08-08T09:00:00Z"),
) = CongestionGenerationCandidate(source(eventUuid, start), savedForecast)

private fun generatedPeriod() = either {
    congestionPeriod(
        start = Instant.parse("2026-08-08T08:00:00Z"),
        end = Instant.parse("2026-08-08T09:00:00Z"),
        area = "会場周辺",
        description = "混雑が予想されます",
    )
}.getOrNull()!!

class GenerateCongestionInfoUseCaseTest : FunSpec({
    test("外部APIの一時失敗をイベント単位の失敗として返す") {
        val eventUuid = EventUuid(Uuid.random())
        val readModel = RecordingGenerationReadModel(
            ArrayDeque(listOf(mapOf(eventUuid to candidate(eventUuid)))),
        )
        val generator = CongestionForecastGenerator {
            CongestionError.ExternalError.GenerationTemporarilyUnavailable.left()
        }

        val result = useCase(readModel, generator, RecordingForecastRepository()).execute(setOf(eventUuid))

        result shouldBe mapOf(
            eventUuid to CongestionInfoResult.Failure(
                CongestionError.ExternalError.GenerationTemporarilyUnavailable,
            ),
        )
    }

    test("ハッシュ一致かつ7日以内の保存済み予測を再利用する") {
        val eventUuid = EventUuid(Uuid.random())
        val saved = SavedForecast(
            forecastUuid = EventCongestionForecastUuid(Uuid.random()),
            generationInputHash = Sha256GenerationInputHashCalculator().calculate(source(eventUuid)),
            generatedAt = USE_CASE_NOW - 7.days,
            periods = listOf(
                SavedCongestionPeriod(
                    start = generatedPeriod().start,
                    end = generatedPeriod().end,
                    area = "会場周辺",
                    description = "混雑が予想されます",
                ),
            ),
        )
        val readModel = RecordingGenerationReadModel(
            ArrayDeque(listOf(mapOf(eventUuid to candidate(eventUuid, saved)))),
        )
        val generator = RecordingGenerator()
        val repository = RecordingForecastRepository()
        val useCase = useCase(readModel, generator, repository)

        val result = useCase.execute(setOf(eventUuid))

        result.keys shouldContainExactly listOf(eventUuid)
        result[eventUuid] shouldBe CongestionInfoResult.Success(
            CongestionSummary("17〜18時に混雑予測あり"),
        )
        generator.calls shouldBe 0
        repository.calls shouldBe emptyList()
    }

    test("生成後に入力hashが変わった予定は保存も結果返却もしない") {
        val eventUuid = EventUuid(Uuid.random())
        val initial = candidate(eventUuid)
        val changed = candidate(eventUuid, start = initial.source.start + 1.hours)
        val readModel = RecordingGenerationReadModel(
            ArrayDeque(listOf(mapOf(eventUuid to initial), mapOf(eventUuid to changed))),
        )
        val generator = RecordingGenerator()
        val repository = RecordingForecastRepository()

        val result = useCase(readModel, generator, repository).execute(setOf(eventUuid))

        result shouldBe mapOf(
            eventUuid to CongestionInfoResult.Failure(
                CongestionError.GenerationError.GenerationInputChanged,
            ),
        )
        repository.calls shouldBe emptyList()
        readModel.calls shouldBe listOf(setOf(eventUuid), setOf(eventUuid))
    }

    test("生成後の再取得で予定が消えていた場合は生成元なしを返す") {
        val eventUuid = EventUuid(Uuid.random())
        val readModel = RecordingGenerationReadModel(
            ArrayDeque(listOf(mapOf(eventUuid to candidate(eventUuid)), emptyMap())),
        )
        val repository = RecordingForecastRepository()

        val result = useCase(readModel, RecordingGenerator(), repository).execute(setOf(eventUuid))

        result shouldBe mapOf(
            eventUuid to CongestionInfoResult.Failure(
                CongestionError.GenerationError.GenerationSourceNotFound,
            ),
        )
        repository.calls shouldBe emptyList()
    }

    test("DB基盤例外は予定単位へ握り潰さず伝播する") {
        val eventUuid = EventUuid(Uuid.random())
        val failure = IllegalStateException("database down")
        val readModel = RecordingGenerationReadModel(
            ArrayDeque(listOf(mapOf(eventUuid to candidate(eventUuid)), mapOf(eventUuid to candidate(eventUuid)))),
        )

        shouldThrow<IllegalStateException> {
            useCase(readModel, RecordingGenerator(), RecordingForecastRepository(failure)).execute(setOf(eventUuid))
        }.message shouldBe "database down"
    }

    test("検証済み生成結果が集約の不変条件に違反した場合は処理全体を失敗させる") {
        val eventUuid = EventUuid(Uuid.random())
        val readModel = RecordingGenerationReadModel(
            ArrayDeque(listOf(mapOf(eventUuid to candidate(eventUuid)), mapOf(eventUuid to candidate(eventUuid)))),
        )

        shouldThrow<IllegalStateException> {
            useCase(
                readModel = readModel,
                generator = RecordingGenerator(),
                repository = RecordingForecastRepository(),
                hashCalculator = GenerationInputHashCalculator { "invalid-hash" },
            ).execute(setOf(eventUuid))
        }.message shouldBe "検証済み混雑予測の集約構築に失敗しました: INVALID_GENERATION_INPUT_HASH"
    }

    test("複数予定の予測を1トランザクションで保存する") {
        val firstEventUuid = EventUuid(Uuid.random())
        val secondEventUuid = EventUuid(Uuid.random())
        val candidates = mapOf(
            firstEventUuid to candidate(firstEventUuid),
            secondEventUuid to candidate(secondEventUuid),
        )
        val readModel = RecordingGenerationReadModel(
            ArrayDeque(listOf(candidates, candidates)),
        )
        val repository = RecordingForecastRepository()
        val transactions = RecordingCongestionTransactionRunner()

        val result = useCase(
            readModel = readModel,
            generator = RecordingGenerator(),
            repository = repository,
            transactions = transactions,
        ).execute(candidates.keys)

        result.values shouldContainExactly listOf(
            CongestionInfoResult.Success(CongestionSummary("17〜18時に混雑予測あり")),
            CongestionInfoResult.Success(CongestionSummary("17〜18時に混雑予測あり")),
        )
        repository.calls.size shouldBe 2
        transactions.inTransactionCalls shouldBe 1
    }

    test("空入力では生成処理を開始しない") {
        val readModel = RecordingGenerationReadModel(ArrayDeque())
        val generator = RecordingGenerator()
        val repository = RecordingForecastRepository()

        useCase(readModel, generator, repository).execute(emptySet()) shouldBe emptyMap()
        readModel.calls shouldBe emptyList()
        generator.calls shouldBe 0
    }
})

private class RecordingGenerator : CongestionForecastGenerator {
    var calls = 0

    override suspend fun generate(source: CongestionGenerationSource) = listOf(generatedPeriod()).right().also {
        calls += 1
    }
}

private fun useCase(
    readModel: CongestionGenerationReadModel,
    generator: CongestionForecastGenerator,
    repository: EventCongestionForecastRepository,
    hashCalculator: GenerationInputHashCalculator = Sha256GenerationInputHashCalculator(),
    transactions: TransactionRunner = ImmediateCongestionTransactionRunner,
) = GenerateCongestionInfoUseCase(
    readModel = readModel,
    generator = generator,
    forecasts = repository,
    transactions = transactions,
    clock = UseCaseClock,
    maxConcurrency = 10,
    hashCalculator = hashCalculator,
)
