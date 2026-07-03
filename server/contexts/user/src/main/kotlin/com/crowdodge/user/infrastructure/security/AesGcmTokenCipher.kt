package com.crowdodge.user.infrastructure.security

import com.crowdodge.user.application.port.TokenCipher
import java.nio.charset.StandardCharsets.UTF_8
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class AesGcmTokenCipher(
    base64EncodedKey: String,
    private val secureRandom: SecureRandom = SecureRandom(),
) : TokenCipher {
    private val key = decodeKey(base64EncodedKey)
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()

    override fun encrypt(plainText: String): String {
        val nonce = ByteArray(NONCE_LENGTH_BYTES).also(secureRandom::nextBytes)
        val cipherText = cipher(Cipher.ENCRYPT_MODE, nonce)
            .doFinal(plainText.toByteArray(UTF_8))

        return buildString {
            append(VERSION)
            append(SEPARATOR)
            append(encoder.encodeToString(nonce))
            append(SEPARATOR)
            append(encoder.encodeToString(cipherText))
        }
    }

    override fun decrypt(encodedCipherText: String): String {
        val parts = encodedCipherText.split(SEPARATOR)
        require(parts.size == CIPHER_TEXT_PARTS) { "Invalid token cipher text format." }
        require(parts[0] == VERSION) { "Unsupported token cipher text version." }

        val nonce = decoder.decode(parts[1])
        require(nonce.size == NONCE_LENGTH_BYTES) { "Invalid token cipher nonce length." }

        val plainText = cipher(Cipher.DECRYPT_MODE, nonce)
            .doFinal(decoder.decode(parts[2]))

        return plainText.toString(UTF_8)
    }

    private fun cipher(mode: Int, nonce: ByteArray): Cipher =
        Cipher.getInstance(TRANSFORMATION).apply {
            init(mode, SecretKeySpec(key, KEY_ALGORITHM), GCMParameterSpec(TAG_LENGTH_BITS, nonce))
        }

    private fun decodeKey(base64EncodedKey: String): ByteArray {
        val decodedKey = try {
            Base64.getDecoder().decode(base64EncodedKey)
        } catch (_: IllegalArgumentException) {
            throw IllegalArgumentException("Token cipher key must be Base64 encoded 32 bytes.")
        }

        require(decodedKey.size == KEY_LENGTH_BYTES) { "Token cipher key must be 32 bytes." }
        return decodedKey
    }

    private companion object {
        const val VERSION = "v1"
        const val SEPARATOR = "."
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALGORITHM = "AES"
        const val KEY_LENGTH_BYTES = 32
        const val NONCE_LENGTH_BYTES = 12
        const val TAG_LENGTH_BITS = 128
        const val CIPHER_TEXT_PARTS = 3
    }
}
