package com.crowdodge.congestion.domain.service

import arrow.core.raise.either
import com.crowdodge.congestion.domain.model.CongestionPeriod
import com.crowdodge.congestion.domain.model.CongestionPeriod.Companion.congestionPeriod
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class CongestionSummaryFormatterTest : FunSpec({

    val formatter = CongestionSummaryFormatter()

    test("24時間以下は期間を時刻順に連結する") {
        val result = formatter.format(
            eventStart = Instant.parse("2026-08-01T01:00:00Z"),
            eventEnd = Instant.parse("2026-08-01T03:00:00Z"),
            periods = listOf(
                period(
                    start = Instant.parse("2026-08-01T11:00:00Z"),
                    end = Instant.parse("2026-08-01T13:00:00Z"),
                    area = "B",
                    description = "desc",
                ),
                period(
                    start = Instant.parse("2026-08-01T09:00:00Z"),
                    end = Instant.parse("2026-08-01T10:30:00Z"),
                    area = "A",
                    description = "desc",
                ),
            ),
        )

        result shouldBe "18〜19:30、20〜22時に混雑予測あり"
    }

    test("24時間を超える予定は混雑期間の開始日だけをまとめる") {
        val result = formatter.format(
            eventStart = Instant.parse("2026-08-01T00:00:00Z"),
            eventEnd = Instant.parse("2026-08-02T01:00:00Z"),
            periods = listOf(
                period(
                    start = Instant.parse("2026-08-02T01:00:00Z"),
                    end = Instant.parse("2026-08-02T03:00:00Z"),
                    area = "B",
                    description = "desc",
                ),
                period(
                    start = Instant.parse("2026-08-01T09:00:00Z"),
                    end = Instant.parse("2026-08-01T10:00:00Z"),
                    area = "A",
                    description = "desc",
                ),
            ),
        )

        result shouldBe "1日、2日に混雑予測あり"
    }

    test("混雑期間が空ならnullを返す") {
        formatter.format(
            eventStart = Instant.parse("2026-08-01T01:00:00Z"),
            eventEnd = Instant.parse("2026-08-01T03:00:00Z"),
            periods = emptyList(),
        ).shouldBeNull()
    }
})

private fun period(start: Instant, end: Instant, area: String, description: String): CongestionPeriod = either {
    congestionPeriod(start, end, area, description)
}.getOrNull()!!
