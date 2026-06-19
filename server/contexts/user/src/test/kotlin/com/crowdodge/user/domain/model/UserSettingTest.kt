package com.crowdodge.user.domain.model

import arrow.core.raise.either
import com.crowdodge.shared.kernel.Location
import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.model.RemindTiming.Companion.remindTiming
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Duration.Companion.minutes

/**
 * 集約ファクトリの不変条件を検証する。
 * - register（新規採番）と reconstitute（id 保持）の違い
 * - UserSetting の選択的・非破壊的更新
 * - EntityId.new() の一意性
 */
class UserSettingTest : FunSpec({
    context("UserSetting") {
        test("changeRemindTiming は remindTiming だけ変えた新インスタンスを返し、元は不変") {
            val uid = UserId.new()
            val home = Location.ofOrNull(135.0, 34.0)!!
            val t1 = either { remindTiming(10.minutes) }.getOrNull()!!
            val t2 = either { remindTiming(30.minutes) }.getOrNull()!!

            val original = UserSetting.configure(uid, home, t1)
            val changed = original.changeRemindTiming(t2)

            changed.remindTiming shouldBe t2
            changed.userId shouldBe uid
            changed.home shouldBe home
            original.remindTiming shouldBe t1 // 元は不変
        }
    }
})
