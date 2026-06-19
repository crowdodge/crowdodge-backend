package com.crowdodge.user.domain.model

import arrow.core.raise.either
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.Email.Companion.email
import com.crowdodge.user.domain.model.FcmToken.Companion.fcmToken
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.GoogleId.Companion.googleId
import com.crowdodge.user.domain.model.RemindTiming.Companion.remindTiming
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.nanoseconds

/**
 * VO スマートコンストラクタの「仕様」を検証する。
 * - Email: trim + 基本形式（@ を1つ含み local/domain が非空）
 * - 不透明トークン(GoogleId/GoogleCalendarId/FcmToken): trim + 非空
 * - RemindTiming: 正の値のみ（0・負は不可）
 */
class ValueObjectTest : FunSpec({

    context("EntityId") {
        test("new() は毎回ユニークな id を採番する") {
            UserCalendarId.new() shouldNotBe UserCalendarId.new()
            UserDeviceId.new() shouldNotBe UserDeviceId.new()
        }
    }

    context("Email") {
        test("前後空白を trim する") {
            either { email("   a@b   ") }.shouldBeRight().value shouldBe "a@b"
        }
        test("空・空白のみは BlankEmail") {
            either { email("") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankEmail
            either { email("   ") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankEmail
        }
        test("基本形式でなければ InvalidEmail") {
            listOf("abc", "a@", "@b", "a@b@c").forEach { input ->
                either { email(input) }.shouldBeLeft() shouldBe UserError.ValidationError.InvalidEmail
            }
        }
        test("妥当な形式は成功し値を保持する") {
            either { email("user@example.com") }.shouldBeRight().value shouldBe "user@example.com"
        }
    }

    context("不透明トークン（trim + 非空）") {
        test("GoogleId は trim し、空白のみは BlankGoogleId") {
            either { googleId("  gid  ") }.shouldBeRight().value shouldBe "gid"
            either { googleId("   ") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankGoogleId
        }
        test("GoogleCalendarId は trim し、空白のみは BlankGoogleCalendarId") {
            either { googleCalendarId("  cal  ") }.shouldBeRight().value shouldBe "cal"
            either { googleCalendarId("   ") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankGoogleCalendarId
        }
        test("FcmToken は trim し、空白のみは BlankFcmToken") {
            either { fcmToken("  tok  ") }.shouldBeRight().value shouldBe "tok"
            either { fcmToken("   ") }.shouldBeLeft() shouldBe UserError.ValidationError.BlankFcmToken
        }
    }

    context("RemindTiming（正の値のみ）") {
        test("0 と負値は InvalidRemindTiming") {
            either { remindTiming(Duration.ZERO) }.shouldBeLeft() shouldBe UserError.ValidationError.InvalidRemindTiming
            either { remindTiming(-(1.minutes)) }.shouldBeLeft() shouldBe UserError.ValidationError.InvalidRemindTiming
        }
        test("正の値は成功し値を保持する") {
            either { remindTiming(10.minutes) }.shouldBeRight().duration shouldBe 10.minutes
            either { remindTiming(1.nanoseconds) }.shouldBeRight()
        }
    }

    context("home（kernel Location の境界を UserError に変換）") {
        test("妥当な座標は成功する") {
            either { home(135.5, 34.7) }.shouldBeRight()
        }
        test("範囲外は InvalidHomeLocation") {
            either { home(200.0, 0.0) }.shouldBeLeft() shouldBe UserError.ValidationError.InvalidHomeLocation
        }
    }
})
