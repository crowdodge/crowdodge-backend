package com.crowdodge.congestion.infrastructure.hash

import com.crowdodge.congestion.application.port.CongestionDestination
import com.crowdodge.congestion.application.port.CongestionGenerationSource
import com.crowdodge.congestion.application.port.CongestionRoute
import com.crowdodge.congestion.application.port.CongestionRouteStep
import com.crowdodge.congestion.domain.model.EventUuid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant
import kotlin.uuid.Uuid

class Sha256GenerationInputHashCalculatorTest : FunSpec({
    val start = Instant.parse("2026-08-01T01:00:00Z")
    val end = Instant.parse("2026-08-01T03:00:00Z")
    val calculator = Sha256GenerationInputHashCalculator()

    fun source(
        destination: CongestionDestination = CongestionDestination("venue", 35.6, 139.7),
        route: CongestionRoute = CongestionRoute(
            listOf(CongestionRouteStep("station-a", "station-b", "line", "train", listOf("station-c"))),
        ),
    ) = CongestionGenerationSource(
        eventUuid = EventUuid(Uuid.random()),
        start = start,
        end = end,
        isAllDay = false,
        destination = destination,
        outboundRoute = route,
        travelDuration = 1.hours,
    )

    test("同じ生成入力は同じhashになる") {
        calculator.calculate(source()) shouldBe calculator.calculate(source())
    }

    test("予定時刻、終日、目的地、経路、移動時間の変更はhashを変える") {
        val base = source()

        calculator.calculate(base.copy(end = end.plus(1.hours))) shouldNotBe calculator.calculate(base)
        calculator.calculate(base.copy(isAllDay = true)) shouldNotBe calculator.calculate(base)
        calculator.calculate(base.copy(destination = base.destination.copy(name = "changed"))) shouldNotBe
            calculator.calculate(base)
        calculator.calculate(
            base.copy(
                outboundRoute = base.outboundRoute.copy(
                    steps = base.outboundRoute.steps + base.outboundRoute.steps.single().copy(toName = "changed"),
                ),
            ),
        ) shouldNotBe calculator.calculate(base)
        calculator.calculate(base.copy(travelDuration = 2.hours)) shouldNotBe calculator.calculate(base)
    }
})
