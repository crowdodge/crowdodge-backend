package com.crowdodge.user.application.command

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import arrow.core.right
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.AppRefreshToken
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.application.port.GoogleAuthorization
import com.crowdodge.user.application.port.GoogleIdentity
import com.crowdodge.user.application.port.GoogleOAuthGateway
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.AuthRefreshTokenHash
import com.crowdodge.user.domain.model.AuthRefreshTokenHash.Companion.authRefreshTokenHash
import com.crowdodge.user.domain.model.Email
import com.crowdodge.user.domain.model.Email.Companion.email
import com.crowdodge.user.domain.model.GoogleAccessToken
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.model.GoogleCalendarId
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.GoogleId
import com.crowdodge.user.domain.model.GoogleId.Companion.googleId
import com.crowdodge.user.domain.model.GoogleRefreshToken
import com.crowdodge.user.domain.model.GoogleRefreshToken.Companion.googleRefreshToken
import com.crowdodge.user.domain.model.GoogleSubject
import com.crowdodge.user.domain.model.GoogleSubject.Companion.googleSubject
import com.crowdodge.user.domain.model.GrantedGoogleScopes
import com.crowdodge.user.domain.model.GrantedGoogleScopes.Companion.grantedGoogleScopes
import com.crowdodge.user.domain.model.User
import com.crowdodge.user.domain.model.UserAuthRefreshToken
import com.crowdodge.user.domain.model.UserAuthRefreshTokenUuid
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import com.crowdodge.user.domain.repository.UserRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlin.time.Instant

class AuthenticateWithGoogleUseCaseTest : FunSpec({
    val redirectUri = "https://example.com/oauth/callback"
    val codeVerifier = "code-verifier"
    val now = Instant.parse("2026-06-27T00:00:00Z")
    val refreshExpiry = Instant.parse("2026-07-27T00:00:00Z")

    test("初回Google認証ではカレンダーを選択しない") {
        val transactionRunner = RecordingTransactionRunner()
        val users = FakeUserRepository()
        val calendars = FakeUserCalendarRepository(transactionRunner)
        val credentials = FakeUserGoogleCredentialRepository(transactionRunner)
        val refreshTokens = FakeUserAuthRefreshTokenRepository(transactionRunner)
        val tokenPort = FakeAppTokenPort(refreshExpiry)
        val oauthGateway = FakeGoogleOAuthGateway(
            authorization = authorization(
                googleSubject = "google-subject-1",
                email = "new-user@example.com",
                accessToken = "google-access-1",
                refreshToken = "google-refresh-1",
                expiresAt = Instant.parse("2026-06-27T01:00:00Z"),
                grantedScopes = "openid email profile https://www.googleapis.com/auth/calendar",
            ),
        )
        val useCase = AuthenticateWithGoogleUseCase(
            googleOAuthGateway = oauthGateway,
            userRepository = users,
            userCalendarRepository = calendars,
            userGoogleCredentialRepository = credentials,
            userAuthRefreshTokenRepository = refreshTokens,
            appTokenPort = tokenPort,
            transactionRunner = transactionRunner,
        )

        val result = useCase.handle(
            AuthenticateWithGoogleCommand(
                authorizationCode = "auth-code",
                redirectUri = redirectUri,
                codeVerifier = codeVerifier,
            ),
        )

        result shouldBe
            AuthenticateWithGoogleResult(
                accessToken = "app-access-token",
                refreshToken = "app-refresh-token",
                refreshTokenExpiresAt = refreshExpiry,
            ).right()
        oauthGateway.wasCalledInsideTransaction shouldBe false
        transactionRunner.inTransactionCalls shouldBe 1
        users.createdUsers shouldHaveSize 1
        calendars.createdCalendars shouldBe emptyList()
        credentials.upsertedCredentials shouldHaveSize 1
        refreshTokens.createdTokens shouldHaveSize 1
        credentials.upsertedCredentials.single().refreshToken?.value shouldBe "google-refresh-1"
        tokenPort.accessTokenIssuedAfterCommit shouldBe true
    }

    test("serverAuthCodeフロー（redirectUri/codeVerifierがnull）でも認証に成功する") {
        val transactionRunner = RecordingTransactionRunner()
        val users = FakeUserRepository()
        val calendars = FakeUserCalendarRepository(transactionRunner)
        val credentials = FakeUserGoogleCredentialRepository(transactionRunner)
        val refreshTokens = FakeUserAuthRefreshTokenRepository(transactionRunner)
        val tokenPort = FakeAppTokenPort(refreshExpiry)
        val oauthGateway = FakeGoogleOAuthGateway(
            authorization = authorization(
                googleSubject = "google-subject-1",
                email = "mobile-user@example.com",
                accessToken = "google-access-1",
                refreshToken = "google-refresh-1",
                expiresAt = Instant.parse("2026-06-27T01:00:00Z"),
                grantedScopes = "openid email profile https://www.googleapis.com/auth/calendar",
            ),
        )
        val useCase = AuthenticateWithGoogleUseCase(
            googleOAuthGateway = oauthGateway,
            userRepository = users,
            userCalendarRepository = calendars,
            userGoogleCredentialRepository = credentials,
            userAuthRefreshTokenRepository = refreshTokens,
            appTokenPort = tokenPort,
            transactionRunner = transactionRunner,
        )

        val result = useCase.handle(
            AuthenticateWithGoogleCommand(
                authorizationCode = "server-auth-code",
                redirectUri = null,
                codeVerifier = null,
            ),
        )

        result shouldBe
            AuthenticateWithGoogleResult(
                accessToken = "app-access-token",
                refreshToken = "app-refresh-token",
                refreshTokenExpiresAt = refreshExpiry,
            ).right()
        users.createdUsers shouldHaveSize 1
        credentials.upsertedCredentials shouldHaveSize 1
        refreshTokens.createdTokens shouldHaveSize 1
    }

    test("再ログインは既存利用者を再利用し refresh token が省略された場合は既存値を維持する") {
        val transactionRunner = RecordingTransactionRunner()
        val existingUser = User.register(gid("google-subject-1"), mail("existing@example.com"))
        val existingCredential = UserGoogleCredential(
            userUuid = existingUser.userUuid,
            googleSubject = gsub("google-subject-1"),
            accessToken = gat("stale-access"),
            refreshToken = grt("persisted-refresh"),
            accessTokenExpiresAt = Instant.parse("2026-06-27T00:30:00Z"),
            grantedScopes = scopes("openid email"),
        )
        val users = FakeUserRepository(existingUser)
        val calendars = FakeUserCalendarRepository(
            transactionRunner = transactionRunner,
            existingCalendars = mutableListOf(UserCalendar.select(existingUser.userUuid, gcid("primary"))),
        )
        val credentials = FakeUserGoogleCredentialRepository(
            transactionRunner = transactionRunner,
            existingCredential = existingCredential,
        )
        val refreshTokens = FakeUserAuthRefreshTokenRepository(transactionRunner)
        val tokenPort = FakeAppTokenPort(refreshExpiry)
        val oauthGateway = FakeGoogleOAuthGateway(
            authorization = authorization(
                googleSubject = "google-subject-1",
                email = "existing@example.com",
                accessToken = "google-access-2",
                refreshToken = null,
                expiresAt = Instant.parse("2026-06-27T02:00:00Z"),
                grantedScopes = "openid email profile",
            ),
        )
        val useCase = AuthenticateWithGoogleUseCase(
            googleOAuthGateway = oauthGateway,
            userRepository = users,
            userCalendarRepository = calendars,
            userGoogleCredentialRepository = credentials,
            userAuthRefreshTokenRepository = refreshTokens,
            appTokenPort = tokenPort,
            transactionRunner = transactionRunner,
        )

        val result = useCase.handle(
            AuthenticateWithGoogleCommand(
                authorizationCode = "auth-code",
                redirectUri = redirectUri,
                codeVerifier = codeVerifier,
            ),
        )

        result shouldBe
            AuthenticateWithGoogleResult(
                accessToken = "app-access-token",
                refreshToken = "app-refresh-token",
                refreshTokenExpiresAt = refreshExpiry,
            ).right()
        users.createdUsers shouldHaveSize 0
        calendars.createdCalendars shouldHaveSize 0
        credentials.upsertedCredentials.single().userUuid shouldBe existingUser.userUuid
        credentials.upsertedCredentials.single().refreshToken?.value shouldBe "persisted-refresh"
    }

    test("OAuth 交換が失敗した場合は DB 操作を一切行わない") {
        val transactionRunner = RecordingTransactionRunner()
        val users = FakeUserRepository()
        val calendars = FakeUserCalendarRepository(transactionRunner)
        val credentials = FakeUserGoogleCredentialRepository(transactionRunner)
        val refreshTokens = FakeUserAuthRefreshTokenRepository(transactionRunner)
        val tokenPort = FakeAppTokenPort(refreshExpiry)
        val useCase = AuthenticateWithGoogleUseCase(
            googleOAuthGateway = FakeGoogleOAuthGateway(error = UserError.ExternalError.GoogleOAuthError),
            userRepository = users,
            userCalendarRepository = calendars,
            userGoogleCredentialRepository = credentials,
            userAuthRefreshTokenRepository = refreshTokens,
            appTokenPort = tokenPort,
            transactionRunner = transactionRunner,
        )

        val result = useCase.handle(
            AuthenticateWithGoogleCommand(
                authorizationCode = "bad-code",
                redirectUri = redirectUri,
                codeVerifier = codeVerifier,
            ),
        )

        result shouldBe UserError.ExternalError.GoogleOAuthError.left()
        transactionRunner.inTransactionCalls shouldBe 0
        users.operationCount shouldBe 0
        calendars.operationCount shouldBe 0
        credentials.operationCount shouldBe 0
        refreshTokens.operationCount shouldBe 0
        tokenPort.operationCount shouldBe 0
    }

    test("AppTokenPort は平文 refresh token から保存用 hash を作る境界を持つ") {
        val tokenPort: AppTokenPort = FakeAppTokenPort(refreshExpiry)

        val tokenHash = tokenPort.hashRefreshToken("app-refresh-token")

        tokenHash shouldBe authHash("b".repeat(64))
    }

    test("新規登録時の email 一意制約違反は DuplicateEmail に写像する") {
        val transactionRunner = RecordingTransactionRunner()
        val users = FakeUserRepository(createResult = UserError.ConflictError.DuplicateEmail.left())
        val calendars = FakeUserCalendarRepository(transactionRunner)
        val credentials = FakeUserGoogleCredentialRepository(transactionRunner)
        val refreshTokens = FakeUserAuthRefreshTokenRepository(transactionRunner)
        val tokenPort = FakeAppTokenPort(refreshExpiry)
        val useCase = AuthenticateWithGoogleUseCase(
            googleOAuthGateway = FakeGoogleOAuthGateway(
                authorization = authorization(
                    googleSubject = "google-subject-2",
                    email = "dup@example.com",
                    accessToken = "google-access-3",
                    refreshToken = "google-refresh-3",
                    expiresAt = now,
                    grantedScopes = "openid email profile",
                ),
            ),
            userRepository = users,
            userCalendarRepository = calendars,
            userGoogleCredentialRepository = credentials,
            userAuthRefreshTokenRepository = refreshTokens,
            appTokenPort = tokenPort,
            transactionRunner = transactionRunner,
        )

        val result = useCase.handle(
            AuthenticateWithGoogleCommand(
                authorizationCode = "auth-code",
                redirectUri = redirectUri,
                codeVerifier = codeVerifier,
            ),
        )

        result shouldBe UserError.ConflictError.DuplicateEmail.left()
        calendars.operationCount shouldBe 0
        credentials.operationCount shouldBe 0
        refreshTokens.operationCount shouldBe 0
        tokenPort.operationCount shouldBe 0
    }
})

private class FakeGoogleOAuthGateway(
    private val authorization: GoogleAuthorization? = null,
    private val error: UserError? = null,
) : GoogleOAuthGateway {
    var wasCalledInsideTransaction: Boolean = false

    override suspend fun exchange(
        authorizationCode: String,
        redirectUri: String?,
        codeVerifier: String?,
    ): Either<UserError, GoogleAuthorization> {
        wasCalledInsideTransaction = RecordingTransactionRunner.currentlyInTransaction
        return error?.left() ?: authorization!!.right()
    }
}

private class RecordingTransactionRunner : TransactionRunner {
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

private class FakeUserRepository(
    existingUser: User? = null,
    private val createResult: Either<UserError.ConflictError.DuplicateEmail, Unit> = Unit.right(),
) : UserRepository {
    private var currentUser: User? = existingUser
    val createdUsers = mutableListOf<User>()
    var operationCount: Int = 0

    override suspend fun create(user: User): Either<UserError.ConflictError.DuplicateEmail, Unit> {
        operationCount++
        val result = createResult
        if (result.isRight()) {
            currentUser = user
            createdUsers += user
        }
        return result
    }

    override suspend fun update(user: User): Either<UserError.ConflictError.DuplicateEmail, Unit> = Unit.right()

    override suspend fun findByUserUuid(userUuid: UserUuid): User? {
        operationCount++
        return currentUser?.takeIf { it.userUuid == userUuid }
    }

    override suspend fun findByGoogleId(googleId: GoogleId): User? {
        operationCount++
        return currentUser?.takeIf { it.googleId == googleId }
    }
}

private class FakeUserCalendarRepository(
    private val transactionRunner: RecordingTransactionRunner,
    private val existingCalendars: MutableList<UserCalendar> = mutableListOf(),
    private val createResult: Either<UserError.ConflictError.DuplicateCalendar, Unit> = Unit.right(),
) : UserCalendarRepository {
    val createdCalendars = mutableListOf<UserCalendar>()
    var operationCount: Int = 0

    override suspend fun create(userCalendar: UserCalendar): Either<UserError.ConflictError.DuplicateCalendar, Unit> {
        check(RecordingTransactionRunner.currentlyInTransaction)
        operationCount++
        if (createResult.isLeft()) {
            return createResult
        }
        existingCalendars += userCalendar
        createdCalendars += userCalendar
        return createResult
    }

    override suspend fun delete(
        userUuid: UserUuid,
        userCalendarUuid: com.crowdodge.user.domain.model.UserCalendarUuid,
    ) = Unit

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserCalendar> {
        check(RecordingTransactionRunner.currentlyInTransaction)
        operationCount++
        return existingCalendars.filter { it.userUuid == userUuid }
    }
}

private class FakeUserGoogleCredentialRepository(
    private val transactionRunner: RecordingTransactionRunner,
    existingCredential: UserGoogleCredential? = null,
) : UserGoogleCredentialRepository {
    private var credential: UserGoogleCredential? = existingCredential
    val upsertedCredentials = mutableListOf<UserGoogleCredential>()
    var operationCount: Int = 0

    override suspend fun findByUserUuid(userUuid: UserUuid): UserGoogleCredential? {
        check(RecordingTransactionRunner.currentlyInTransaction)
        operationCount++
        return credential?.takeIf { it.userUuid == userUuid }
    }

    override suspend fun upsert(credential: UserGoogleCredential) {
        check(RecordingTransactionRunner.currentlyInTransaction)
        operationCount++
        this.credential = credential
        upsertedCredentials += credential
    }

    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: GoogleAccessToken,
        accessTokenExpiresAt: Instant,
    ) = Unit
}

private class FakeUserAuthRefreshTokenRepository(
    private val transactionRunner: RecordingTransactionRunner,
) : UserAuthRefreshTokenRepository {
    val createdTokens = mutableListOf<UserAuthRefreshToken>()
    var operationCount: Int = 0

    override suspend fun create(refreshToken: UserAuthRefreshToken) {
        check(RecordingTransactionRunner.currentlyInTransaction)
        operationCount++
        createdTokens += refreshToken
    }

    override suspend fun findByHash(tokenHash: AuthRefreshTokenHash): UserAuthRefreshToken? = null

    override suspend fun consumeUsableByHash(
        tokenHash: AuthRefreshTokenHash,
        now: Instant,
    ): UserAuthRefreshToken? = null

    override suspend fun revoke(
        refreshTokenUuid: UserAuthRefreshTokenUuid,
        revokedAt: Instant,
    ) = Unit
}

private class FakeAppTokenPort(
    private val refreshExpiry: Instant,
) : AppTokenPort {
    var operationCount: Int = 0
    var accessTokenIssuedAfterCommit: Boolean = false

    override fun issueRefreshToken(userUuid: UserUuid): AppRefreshToken {
        operationCount++
        return AppRefreshToken(
            plainText = "app-refresh-token",
            hash = authHash("a".repeat(64)),
            expiresAt = refreshExpiry,
        )
    }

    override fun hashRefreshToken(plainText: String): AuthRefreshTokenHash {
        operationCount++
        return authHash("b".repeat(64))
    }

    override fun issueAccessToken(userUuid: UserUuid): String {
        operationCount++
        accessTokenIssuedAfterCommit = !RecordingTransactionRunner.currentlyInTransaction
        return "app-access-token"
    }
}

@Suppress("LongParameterList")
private fun authorization(
    googleSubject: String,
    email: String,
    accessToken: String,
    refreshToken: String?,
    expiresAt: Instant,
    grantedScopes: String,
) = GoogleAuthorization(
    identity = GoogleIdentity(googleSubject = googleSubject, email = email),
    accessToken = accessToken,
    refreshToken = refreshToken,
    expiresAt = expiresAt,
    grantedScopes = grantedScopes.split(" ").toSet(),
)

private fun mail(value: String): Email = either { email(value) }.getOrNull() ?: error("invalid email")

private fun gid(value: String): GoogleId = either { googleId(value) }.getOrNull() ?: error("invalid google id")

private fun gcid(value: String): GoogleCalendarId =
    either { googleCalendarId(value) }.getOrNull() ?: error("invalid google calendar id")

private fun gsub(value: String): GoogleSubject =
    either { googleSubject(value) }.getOrNull() ?: error("invalid google subject")

private fun gat(value: String): GoogleAccessToken =
    either { googleAccessToken(value) }.getOrNull() ?: error("invalid google access token")

private fun grt(value: String): GoogleRefreshToken =
    either { googleRefreshToken(value) }.getOrNull() ?: error("invalid google refresh token")

private fun scopes(value: String): GrantedGoogleScopes =
    either { grantedGoogleScopes(value) }.getOrNull() ?: error("invalid scopes")

private fun authHash(value: String): AuthRefreshTokenHash =
    either { authRefreshTokenHash(value) }.getOrNull() ?: error("invalid auth refresh token hash")
