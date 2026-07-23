package com.crowdodge.user.application.port

interface TokenCipher {
    fun encrypt(plainText: String): String

    fun decrypt(encodedCipherText: String): String
}
