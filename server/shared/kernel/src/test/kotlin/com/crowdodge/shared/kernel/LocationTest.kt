package com.crowdodge.shared.kernel

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.checkAll

/**
 * Location は地理座標の一般的セマンティクスを検証する（実装の固定でなく仕様）。
 * 経度 [-180,180]・緯度 [-90,90]（いずれも境界を含む）。NaN/Infinity は無効。
 */
class LocationTest : FunSpec({

    test("境界値（±180 / ±90）は有効（inclusive）") {
        Location.ofOrNull(180.0, 90.0).shouldNotBeNull()
        Location.ofOrNull(-180.0, -90.0).shouldNotBeNull()
    }

    test("境界をわずかに超えると無効") {
        Location.ofOrNull(180.0001, 0.0).shouldBeNull()
        Location.ofOrNull(-180.0001, 0.0).shouldBeNull()
        Location.ofOrNull(0.0, 90.0001).shouldBeNull()
        Location.ofOrNull(0.0, -90.0001).shouldBeNull()
    }

    test("NaN / Infinity は無効") {
        Location.ofOrNull(Double.NaN, 0.0).shouldBeNull()
        Location.ofOrNull(0.0, Double.NaN).shouldBeNull()
        Location.ofOrNull(Double.POSITIVE_INFINITY, 0.0).shouldBeNull()
        Location.ofOrNull(0.0, Double.NEGATIVE_INFINITY).shouldBeNull()
    }

    test("有効な座標は値を保持する") {
        val loc = Location.ofOrNull(135.5, 34.7).shouldNotBeNull()
        loc.longitude shouldBe 135.5
        loc.latitude shouldBe 34.7
    }

    test("範囲内の有限な座標は常に生成できる（プロパティ）") {
        checkAll(Arb.double(-180.0, 180.0), Arb.double(-90.0, 90.0)) { lon, lat ->
            if (lon.isFinite() && lat.isFinite()) {
                Location.ofOrNull(lon, lat).shouldNotBeNull()
            }
        }
    }
})
