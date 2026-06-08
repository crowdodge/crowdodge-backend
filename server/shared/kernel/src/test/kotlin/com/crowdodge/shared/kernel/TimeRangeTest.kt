package com.crowdodge.shared.kernel

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimeRangeTest {
    private val t0 = Instant.parse("2026-06-03T10:00:00Z")
    private val t1 = Instant.parse("2026-06-03T11:00:00Z")
    private val t2 = Instant.parse("2026-06-03T12:00:00Z")

    @Test
    fun `end が start より前なら生成に失敗する`() {
        assertFailsWith<IllegalArgumentException> { TimeRange(t1, t0) }
    }

    @Test
    fun `重なる範囲を検出する`() {
        assertTrue(TimeRange(t0, t2).overlaps(TimeRange(t1, t2)))
    }

    @Test
    fun `隣接するだけの範囲は重ならない`() {
        assertFalse(TimeRange(t0, t1).overlaps(TimeRange(t1, t2)))
    }
}
