package com.crowdodge.shared.kernel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

class AppTimeTest : FunSpec({
    test("businessTimeZone は日本時間を表す Asia/Tokyo") {
        AppTime.businessTimeZone shouldBe TimeZone.of("Asia/Tokyo")
    }

    test("終日予定の日付境界は日本時間の 0 時として Instant に変換する") {
        AppTime.startOfBusinessDate(LocalDate(2026, 7, 1)) shouldBe
            Instant.parse("2026-06-30T15:00:00Z")
    }
})
