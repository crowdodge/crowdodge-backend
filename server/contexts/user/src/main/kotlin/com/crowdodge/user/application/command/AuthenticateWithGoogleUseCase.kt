package com.crowdodge.user.application.command

import arrow.core.Either
import arrow.core.left
import arrow.core.raise.either
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.AppRefreshToken
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.application.port.GoogleOAuthGateway
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.Email.Companion.email
import com.crowdodge.user.domain.model.GoogleAccessToken.Companion.googleAccessToken
import com.crowdodge.user.domain.model.GoogleId.Companion.googleId
import com.crowdodge.user.domain.model.GoogleRefreshToken.Companion.googleRefreshToken
import com.crowdodge.user.domain.model.GoogleSubject.Companion.googleSubject
import com.crowdodge.user.domain.model.GrantedGoogleScopes.Companion.grantedGoogleScopes
import com.crowdodge.user.domain.model.User
import com.crowdodge.user.domain.model.UserAuthRefreshToken
import com.crowdodge.user.domain.model.UserAuthRefreshTokenUuid
import com.crowdodge.user.domain.model.UserGoogleCredential
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import com.crowdodge.user.domain.repository.UserRepository

data class AuthenticateWithGoogleCommand(
    val authorizationCode: String,
    val redirectUri: String,
    val codeVerifier: String,
)

data class AuthenticateWithGoogleResult(
    val accessToken: String,
    val refreshToken: String,
    val refreshTokenExpiresAt: kotlin.time.Instant,
)

@Suppress("LongParameterList", "UnusedPrivateProperty")
class AuthenticateWithGoogleUseCase(
    private val googleOAuthGateway: GoogleOAuthGateway,
    private val userRepository: UserRepository,
    userCalendarRepository: UserCalendarRepository,
    private val userGoogleCredentialRepository: UserGoogleCredentialRepository,
    private val userAuthRefreshTokenRepository: UserAuthRefreshTokenRepository,
    private val appTokenPort: AppTokenPort,
    private val transactionRunner: TransactionRunner,
) {
    suspend fun handle(command: AuthenticateWithGoogleCommand): Either<UserError, AuthenticateWithGoogleResult> {
        val authorization = googleOAuthGateway.exchange(
            authorizationCode = command.authorizationCode,
            redirectUri = command.redirectUri,
            codeVerifier = command.codeVerifier,
        ).fold({ return it.left() }, { it })

        val committed = transactionRunner.inTransaction {
            either<UserError, Pair<UserUuid, AppRefreshToken>> {
                val signedInGoogleId = googleId(authorization.identity.googleSubject)
                val signedInEmail = email(authorization.identity.email)
                val signedInGoogleSubject = googleSubject(authorization.identity.googleSubject)
                val googleAccessToken = googleAccessToken(authorization.accessToken)
                val googleRefreshToken = authorization.refreshToken?.let { googleRefreshToken(it) }
                val grantedScopes = grantedGoogleScopes(authorization.grantedScopes.joinToString(" "))

                val user = userRepository.findByGoogleId(signedInGoogleId) ?: run {
                    val newUser = User.register(signedInGoogleId, signedInEmail)
                    userRepository.create(newUser).bind()
                    newUser
                }

                val credential = userGoogleCredentialRepository.findByUserUuid(user.userUuid)
                    ?.apply {
                        reauthorize(
                            newAccessToken = googleAccessToken,
                            newRefreshToken = googleRefreshToken,
                            newExpiresAt = authorization.expiresAt,
                            scopes = grantedScopes,
                        )
                    }
                    ?: UserGoogleCredential(
                        userUuid = user.userUuid,
                        googleSubject = signedInGoogleSubject,
                        accessToken = googleAccessToken,
                        refreshToken = googleRefreshToken,
                        accessTokenExpiresAt = authorization.expiresAt,
                        grantedScopes = grantedScopes,
                    )
                userGoogleCredentialRepository.upsert(credential)

                val appRefreshToken = appTokenPort.issueRefreshToken(user.userUuid)
                userAuthRefreshTokenRepository.create(
                    UserAuthRefreshToken(
                        refreshTokenUuid = UserAuthRefreshTokenUuid.new(),
                        userUuid = user.userUuid,
                        tokenHash = appRefreshToken.hash,
                        expiresAt = appRefreshToken.expiresAt,
                        revokedAt = null,
                    ),
                )
                user.userUuid to appRefreshToken
            }
        }

        return committed.map { (userUuid, appRefreshToken) ->
            AuthenticateWithGoogleResult(
                accessToken = appTokenPort.issueAccessToken(userUuid),
                refreshToken = appRefreshToken.plainText,
                refreshTokenExpiresAt = appRefreshToken.expiresAt,
            )
        }
    }
}
