package com.crowdodge.user.domain.model

import arrow.core.raise.either
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.Email.Companion.email
import com.crowdodge.user.domain.model.FcmToken.Companion.fcmToken
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.GoogleId.Companion.googleId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class UserCalendarTest : FunSpec({

    fun mail(value: String = "user@example.com"): Email = either { email(value) }.getOrNull()!!
    fun gid(value: String = "google-id"): GoogleId = either { googleId(value) }.getOrNull()!!
    fun gcid(value: String = "calendar-id"): GoogleCalendarId = either { googleCalendarId(value) }.getOrNull()!!
    fun fcm(value: String = "fcm-token"): FcmToken = either { fcmToken(value) }.getOrNull()!!

    context("UserCalendar") {
        test("select は新しい UserCalendarUuid を採番し、userUuid / googleCalendarId を保持する") {
            val uid = UserUuid.new()
            val cal = gcid("cal-1")
            val c1 = UserCalendar.select(uid, cal)
            val c2 = UserCalendar.select(uid, cal)
            c1.userCalendarUuid shouldNotBe c2.userCalendarUuid // 採番（毎回別 userCalendarUuid）
            c1.userUuid shouldBe uid
            c1.googleCalendarId shouldBe cal
        }
        test("reconstitute は渡した userCalendarUuid とフィールドを保持する（採番しない）") {
            val userCalendarUuid = UserCalendarUuid.new()
            val uid = UserUuid.new()
            val cal = gcid()
            val c = UserCalendar.reconstitute(userCalendarUuid, uid, cal)
            c.userCalendarUuid shouldBe userCalendarUuid
            c.userUuid shouldBe uid
            c.googleCalendarId shouldBe cal
        }
    }
})
