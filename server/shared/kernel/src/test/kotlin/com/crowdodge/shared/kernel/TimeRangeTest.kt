package com.crowdodge.shared.kernel

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.Instant

class TimeRangeTest : FunSpec({
    val t0 = Instant.parse("2026-06-03T10:00:00Z")
    val t1 = Instant.parse("2026-06-03T11:00:00Z")
    val t2 = Instant.parse("2026-06-03T12:00:00Z")

    test("end が start より前なら生成に失敗する") {
        shouldThrow<IllegalArgumentException> { TimeRange(t1, t0) }
    }

    test("重なる範囲を検出する") {
        TimeRange(t0, t2).overlaps(TimeRange(t1, t2)) shouldBe true
    }

    test("隣接するだけの範囲は重ならない") {
        TimeRange(t0, t1).overlaps(TimeRange(t1, t2)) shouldBe false
    }
})
