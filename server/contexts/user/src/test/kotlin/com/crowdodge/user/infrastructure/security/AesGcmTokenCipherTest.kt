package com.crowdodge.user.infrastructure.security

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Base64
import javax.crypto.AEADBadTagException

class AesGcmTokenCipherTest : FunSpec({
    test("同じ平文でも nonce が異なり復号すると元に戻る") {
        val cipher = AesGcmTokenCipher(validBase64Key())

        val first = cipher.encrypt("secret")
        val second = cipher.encrypt("secret")

        first shouldNotBe second
        cipher.decrypt(first) shouldBe "secret"
        cipher.decrypt(second) shouldBe "secret"
    }

    test("32 byte ではない鍵を受け取ると構築時に失敗する") {
        shouldThrow<IllegalArgumentException> {
            AesGcmTokenCipher(Base64.getEncoder().encodeToString(ByteArray(31) { 1 }))
        }
    }

    test("形式不正な暗号文は復号時に失敗する") {
        val cipher = AesGcmTokenCipher(validBase64Key())

        shouldThrow<IllegalArgumentException> {
            cipher.decrypt("broken")
        }
    }

    test("改ざんされた暗号文は復号時に失敗する") {
        val cipher = AesGcmTokenCipher(validBase64Key())
        val encrypted = cipher.encrypt("secret")
        val parts = encrypted.split(".")
        val tamperedCipherText = parts[2].let { value ->
            val firstChar = value.first()
            val replacement = if (firstChar == 'A') 'B' else 'A'
            replacement + value.drop(1)
        }

        shouldThrow<AEADBadTagException> {
            cipher.decrypt("${parts[0]}.${parts[1]}.$tamperedCipherText")
        }
    }
})

private fun validBase64Key(): String = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
