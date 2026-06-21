package com.crowdodge.user.domain.model

import arrow.core.raise.either
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.Email.Companion.email
import com.crowdodge.user.domain.model.GoogleId.Companion.googleId
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

class UserTest : FunSpec({
    fun mail(value: String = "user@example.com"): Email = either { email(value) }.getOrNull()!!
    fun gid(value: String = "google-id"): GoogleId = either { googleId(value) }.getOrNull()!!

    context("User") {
        test("register は呼ぶたびに異なる UserUuid を採番する") {
            val u1 = User.register(gid(), mail())
            val u2 = User.register(gid(), mail())
            u1.userUuid shouldNotBe u2.userUuid
        }
        test("register は渡した googleId / email を保持する") {
            val g = gid("g-1")
            val m = mail("a@b.com")
            val u = User.register(g, m)
            u.googleId shouldBe g
            u.email shouldBe m
        }
        test("reconstitute は渡した UserUuid を保持する（採番しない）") {
            val userUuid = UserUuid.new()
            User.reconstitute(userUuid, gid(), mail()).userUuid shouldBe userUuid
        }
    }
})
