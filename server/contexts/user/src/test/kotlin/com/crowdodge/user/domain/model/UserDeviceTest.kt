package com.crowdodge.user.domain.model

import arrow.core.raise.either
import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.model.FcmToken.Companion.fcmToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class UserDeviceTest : FunSpec({
    fun fcm(value: String = "fcm-token"): FcmToken = either { fcmToken(value) }.getOrNull()!!

    context("UserDevice") {
        test("register は新しい UserDeviceId を採番し、userId / fcmToken を保持する") {
            val uid = UserId.new()
            val token = fcm("token-1")
            val d1 = UserDevice.register(uid, token)
            val d2 = UserDevice.register(uid, token)
            d1.id shouldNotBe d2.id // 採番（毎回別 id）
            d1.userId shouldBe uid
            d1.fcmToken shouldBe token
        }
        test("reconstitute は渡した id とフィールドを保持する（採番しない）") {
            val id = UserDeviceId.new()
            val uid = UserId.new()
            val token = fcm()
            val d = UserDevice.reconstitute(id, uid, token)
            d.id shouldBe id
            d.userId shouldBe uid
            d.fcmToken shouldBe token
        }
    }
})
