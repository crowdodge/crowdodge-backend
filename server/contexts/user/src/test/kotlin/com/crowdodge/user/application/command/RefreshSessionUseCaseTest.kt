package com.crowdodge.user.application.command

import arrow.core.Either
import arrow.core.raise.either
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.AppRefreshToken
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.AuthRefreshTokenHash
import com.crowdodge.user.domain.model.AuthRefreshTokenHash.Companion.authRefreshTokenHash
import com.crowdodge.user.domain.model.UserAuthRefreshToken
import com.crowdodge.user.domain.model.UserAuthRefreshTokenUuid
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class RefreshSessionUseCaseTest : FunSpec({
    val now = Instant.parse("2026-06-28T01:02:03Z")
    val expiresAt = Instant.parse("2026-07-28T01:02:03Z")
    val userUuid = UserUuid.new()
    val oldHash = authHash("a".repeat(64))
    val newHash = authHash("b".repeat(64))

    test("refresh 成功時に旧 hash を revoke し新 hash を同一 transaction で保存し、commit 後に access JWT を発行する") {
        val transactionRunner = RefreshRecordingTransactionRunner()
        val oldToken = UserAuthRefreshToken(
            refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
            userUuid = userUuid,
            tokenHash = oldHash,
            expiresAt = expiresAt,
            revokedAt = null,
        )
        val repository = RefreshFakeUserAuthRefreshTokenRepository(oldToken)
        val tokenPort = RefreshFakeAppTokenPort(
            now = now,
            issuedRefreshToken = AppRefreshToken("new-refresh-token", newHash, expiresAt),
            plainToHash = mapOf("old-refresh-token" to oldHash),
        )
        val useCase = RefreshSessionUseCase(repository, tokenPort, transactionRunner, clock = FixedClock(now))

        val result = useCase.handle(RefreshSessionCommand("old-refresh-token"))

        result shouldBe Either.Right(
            RefreshSessionResult(
                accessToken = "new-access-token",
                refreshToken = "new-refresh-token",
                refreshTokenExpiresAt = expiresAt,
            ),
        )
        repository.revokedTokens shouldBe listOf(oldToken.refreshTokenUuid to now)
        repository.createdTokens.map { it.tokenHash } shouldBe listOf(newHash)
        repository.createCalledInsideTransaction shouldBe true
        repository.consumeCalledInsideTransaction shouldBe true
        repository.consumeCallCount shouldBe 1
        repository.findByHashCallCount shouldBe 0
        repository.revokeCallCount shouldBe 0
        tokenPort.accessTokenIssuedAfterCommit shouldBe true
    }

    test("refresh token consume は同じ hash を1回だけ返し、2回目は null を返す") {
        val oldToken = UserAuthRefreshToken(
            refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
            userUuid = userUuid,
            tokenHash = oldHash,
            expiresAt = expiresAt,
            revokedAt = null,
        )
        val repository = RefreshFakeUserAuthRefreshTokenRepository(oldToken)

        repository.consumeUsableByHash(oldHash, now) shouldBe oldToken
        repository.consumeUsableByHash(oldHash, now) shouldBe null
    }

    test("同じ旧 token の2回目利用は InvalidRefreshToken になる") {
        val transactionRunner = RefreshRecordingTransactionRunner()
        val oldToken = UserAuthRefreshToken(
            refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
            userUuid = userUuid,
            tokenHash = oldHash,
            expiresAt = expiresAt,
            revokedAt = null,
        )
        val repository = RefreshFakeUserAuthRefreshTokenRepository(oldToken)
        val tokenPort = RefreshFakeAppTokenPort(
            now = now,
            issuedRefreshToken = AppRefreshToken("new-refresh-token", newHash, expiresAt),
            plainToHash = mapOf("old-refresh-token" to oldHash),
        )
        val useCase = RefreshSessionUseCase(repository, tokenPort, transactionRunner, clock = FixedClock(now))

        useCase.handle(RefreshSessionCommand("old-refresh-token"))
        val second = useCase.handle(RefreshSessionCommand("old-refresh-token"))

        second.shouldBeLeft() shouldBe UserError.AuthenticationError.InvalidRefreshToken
        repository.createdTokens.size shouldBe 1
        repository.consumeCallCount shouldBe 2
        repository.revokeCallCount shouldBe 0
    }

    test("存在しない token は InvalidRefreshToken になる") {
        val useCase = RefreshSessionUseCase(
            userAuthRefreshTokenRepository = RefreshFakeUserAuthRefreshTokenRepository(null),
            appTokenPort = RefreshFakeAppTokenPort(now, AppRefreshToken("new-refresh-token", newHash, expiresAt)),
            transactionRunner = RefreshRecordingTransactionRunner(),
            clock = FixedClock(now),
        )

        val result = useCase.handle(RefreshSessionCommand("missing-refresh-token"))

        result.shouldBeLeft() shouldBe UserError.AuthenticationError.InvalidRefreshToken
    }

    test("logout は一致する refresh token を transaction 内で revoke し、存在しない場合も成功する") {
        val transactionRunner = RefreshRecordingTransactionRunner()
        val oldToken = UserAuthRefreshToken(
            refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
            userUuid = userUuid,
            tokenHash = oldHash,
            expiresAt = expiresAt,
            revokedAt = null,
        )
        val repository = RefreshFakeUserAuthRefreshTokenRepository(oldToken)
        val tokenPort = RefreshFakeAppTokenPort(
            now = now,
            issuedRefreshToken = AppRefreshToken("new-refresh-token", newHash, expiresAt),
            plainToHash = mapOf("old-refresh-token" to oldHash),
        )
        val useCase = LogoutUseCase(repository, tokenPort, transactionRunner, clock = FixedClock(now))

        useCase.handle(LogoutCommand("old-refresh-token")) shouldBe Either.Right(Unit)
        useCase.handle(LogoutCommand("missing-refresh-token")) shouldBe Either.Right(Unit)

        repository.revokedTokens shouldBe listOf(oldToken.refreshTokenUuid to now)
        repository.consumeCalledInsideTransaction shouldBe true
        repository.consumeCallCount shouldBe 2
        repository.revokeCallCount shouldBe 0
    }
})

private class RefreshRecordingTransactionRunner : TransactionRunner {
    var inTransactionCalls: Int = 0

    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        inTransactionCalls++
        currentlyInTransaction = true
        return try {
            block()
        } finally {
            currentlyInTransaction = false
        }
    }

    override suspend fun <T> readOnly(block: suspend () -> T): T = block()

    companion object {
        var currentlyInTransaction: Boolean = false
    }
}

private class RefreshFakeUserAuthRefreshTokenRepository(
    initialToken: UserAuthRefreshToken?,
) : UserAuthRefreshTokenRepository {
    private val tokens = initialToken?.let { mutableListOf(it) } ?: mutableListOf()
    val createdTokens = mutableListOf<UserAuthRefreshToken>()
    val revokedTokens = mutableListOf<Pair<UserAuthRefreshTokenUuid, Instant>>()
    var createCalledInsideTransaction: Boolean = false
    var consumeCalledInsideTransaction: Boolean = false
    var consumeCallCount: Int = 0
    var findByHashCallCount: Int = 0
    var revokeCallCount: Int = 0

    override suspend fun create(refreshToken: UserAuthRefreshToken) {
        createCalledInsideTransaction = RefreshRecordingTransactionRunner.currentlyInTransaction
        createdTokens += refreshToken
        tokens += refreshToken
    }

    override suspend fun findByHash(tokenHash: AuthRefreshTokenHash): UserAuthRefreshToken? {
        findByHashCallCount++
        return tokens.firstOrNull { it.tokenHash == tokenHash }
    }

    override suspend fun consumeUsableByHash(
        tokenHash: AuthRefreshTokenHash,
        now: Instant,
    ): UserAuthRefreshToken? {
        consumeCalledInsideTransaction = RefreshRecordingTransactionRunner.currentlyInTransaction
        consumeCallCount++
        val token = tokens.firstOrNull { it.tokenHash == tokenHash && it.isUsable(now) } ?: return null
        revokedTokens += token.refreshTokenUuid to now
        token.revoke(now)
        return token
    }

    override suspend fun revoke(refreshTokenUuid: UserAuthRefreshTokenUuid, revokedAt: Instant) {
        consumeCalledInsideTransaction = RefreshRecordingTransactionRunner.currentlyInTransaction
        revokeCallCount++
        revokedTokens += refreshTokenUuid to revokedAt
        tokens.firstOrNull { it.refreshTokenUuid == refreshTokenUuid }?.revoke(revokedAt)
    }
}

private class RefreshFakeAppTokenPort(
    private val now: Instant,
    private val issuedRefreshToken: AppRefreshToken,
    private val plainToHash: Map<String, AuthRefreshTokenHash> = emptyMap(),
) : AppTokenPort {
    var accessTokenIssuedAfterCommit: Boolean = false

    override fun issueRefreshToken(userUuid: UserUuid): AppRefreshToken = issuedRefreshToken

    override fun hashRefreshToken(plainText: String): AuthRefreshTokenHash =
        plainToHash[plainText] ?: authHash("f".repeat(64))

    override fun issueAccessToken(userUuid: UserUuid): String {
        accessTokenIssuedAfterCommit = !RefreshRecordingTransactionRunner.currentlyInTransaction
        return "new-access-token"
    }
}

private class FixedClock(private val now: Instant) : kotlin.time.Clock {
    override fun now(): Instant = now
}

private fun authHash(value: String): AuthRefreshTokenHash =
    either { authRefreshTokenHash(value) }.getOrNull() ?: error("invalid auth refresh token hash")
