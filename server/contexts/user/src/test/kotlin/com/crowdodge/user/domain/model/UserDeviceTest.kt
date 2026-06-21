package com.crowdodge.user.domain.model

import arrow.core.raise.either
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.FcmToken.Companion.fcmToken
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class UserDeviceTest : FunSpec({
    fun fcm(value: String = "fcm-token"): FcmToken = either { fcmToken(value) }.getOrNull()!!

    context("UserDevice") {
        test("register は新しい UserDeviceUuid を採番し、userUuid / fcmToken を保持する") {
            val uid = UserUuid.new()
            val token = fcm("token-1")
            val d1 = UserDevice.register(uid, token)
            val d2 = UserDevice.register(uid, token)
            d1.userDeviceUuid shouldNotBe d2.userDeviceUuid // 採番（毎回別 userDeviceUuid）
            d1.userUuid shouldBe uid
            d1.fcmToken shouldBe token
        }
        test("reconstitute は渡した userDeviceUuid とフィールドを保持する（採番しない）") {
            val userDeviceUuid = UserDeviceUuid.new()
            val uid = UserUuid.new()
            val token = fcm()
            val d = UserDevice.reconstitute(userDeviceUuid, uid, token)
            d.userDeviceUuid shouldBe userDeviceUuid
            d.userUuid shouldBe uid
            d.fcmToken shouldBe token
        }
    }
})
