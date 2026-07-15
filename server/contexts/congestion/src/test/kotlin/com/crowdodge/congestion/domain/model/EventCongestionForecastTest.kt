package com.crowdodge.congestion.domain.model

import arrow.core.raise.either
import com.crowdodge.congestion.domain.error.CongestionError
import com.crowdodge.congestion.domain.model.CongestionPeriod.Companion.congestionPeriod
import com.crowdodge.congestion.domain.model.EventCongestionForecast.Companion.forecast
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant
import kotlin.uuid.Uuid

class EventCongestionForecastTest : FunSpec({
    val start = Instant.parse("2026-08-01T01:00:00Z")
    val end = Instant.parse("2026-08-01T02:00:00Z")

    fun period() = either {
        congestionPeriod(start, end, " 会場周辺 ", " 混雑します ")
    }.shouldBeRight()

    test("有効な入力からforecastを生成し文字列を正規化する") {
        val forecast = either {
            forecast(
                eventUuid = EventUuid(Uuid.random()),
                generationInputHash = "a".repeat(64),
                generatedAt = end,
                periods = listOf(period()),
            )
        }.shouldBeRight()

        forecast.periods.single().area shouldBe "会場周辺"
        forecast.periods.single().description shouldBe "混雑します"
    }

    test("期間の前後関係と空文字を検証する") {
        either { congestionPeriod(end, start, "area", "description") }
            .shouldBeLeft() shouldBe CongestionError.ValidationError.InvalidCongestionPeriodRange
        either { congestionPeriod(start, end, " ", "description") }
            .shouldBeLeft() shouldBe CongestionError.ValidationError.BlankCongestionArea
        either { congestionPeriod(start, end, "area", " ") }
            .shouldBeLeft() shouldBe CongestionError.ValidationError.BlankCongestionDescription
    }

    test("forecastは最大3期間とSHA-256形式のhashを保証する") {
        either {
            forecast(
                EventUuid(Uuid.random()),
                "invalid",
                end,
                emptyList(),
            )
        }.shouldBeLeft() shouldBe CongestionError.ValidationError.InvalidGenerationInputHash

        either {
            forecast(
                EventUuid(Uuid.random()),
                "a".repeat(64),
                end,
                List(4) { period() },
            )
        }.shouldBeLeft() shouldBe CongestionError.ValidationError.TooManyCongestionPeriods
    }
})
