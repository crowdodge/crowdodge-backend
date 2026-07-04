package com.crowdodge.user.infrastructure.google

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.crowdodge.user.application.port.GoogleAuthorization
import com.crowdodge.user.application.port.GoogleIdentity
import com.crowdodge.user.application.port.GoogleOAuthGateway
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.application.port.REQUIRED_GOOGLE_CALENDAR_SCOPES
import com.crowdodge.user.application.port.RefreshedGoogleToken
import com.crowdodge.user.domain.error.UserError
import io.ktor.client.HttpClient
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Base64
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val allowedIssuers = setOf("https://accounts.google.com", "accounts.google.com")

data class GoogleOAuthConfig(
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String,
    val jwksUrl: String = "https://www.googleapis.com/oauth2/v3/certs",
) {
    init {
        require(clientId.isNotBlank()) { "Google OAuth client ID must not be blank" }
    }
}

class GoogleOAuthTokenGateway(
    private val config: GoogleOAuthConfig,
    private val httpClient: HttpClient,
    private val clock: Clock = Clock.System,
) : GoogleOAuthTokenRefreshGateway, GoogleOAuthGateway {
    private val json = Json { ignoreUnknownKeys = true }
    private var cachedJwks: CachedJwks? = null

    @Suppress("ReturnCount")
    override suspend fun exchange(
        authorizationCode: String,
        redirectUri: String,
        codeVerifier: String,
    ): Either<UserError, GoogleAuthorization> {
        val response = runCatching {
            httpClient.post(config.tokenUrl) {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("grant_type", "authorization_code")
                            append("code", authorizationCode)
                            append("client_id", config.clientId)
                            append("redirect_uri", redirectUri)
                            append("code_verifier", codeVerifier)
                            if (config.clientSecret.isNotBlank()) {
                                append("client_secret", config.clientSecret)
                            }
                        },
                    ),
                )
            }
        }.getOrElse { exception ->
            exception.rethrowIfCancellation()
            return oauthError()
        }
        if (!response.status.isSuccess()) return oauthError()

        val token = runCatching {
            json.decodeFromString<AuthorizationTokenResponse>(response.bodyAsText())
        }.getOrElse { exception ->
            exception.rethrowIfCancellation()
            return oauthError()
        }
        val scopes = token.scope.split(" ").map(String::trim).filter(String::isNotBlank).toSet()
        if (!scopes.containsAll(REQUIRED_GOOGLE_CALENDAR_SCOPES)) {
            return UserError.AuthenticationError.MissingGoogleScope.left()
        }

        val identity = verifyIdToken(token.idToken).fold({ return it.left() }, { it })
        return GoogleAuthorization(
            identity = identity,
            accessToken = token.accessToken,
            refreshToken = token.refreshToken,
            expiresAt = clock.now() + token.expiresIn.seconds,
            grantedScopes = scopes,
        ).right()
    }

    @Suppress("ReturnCount", "TooGenericExceptionCaught")
    override suspend fun refresh(
        refreshToken: String,
    ): Either<UserError, RefreshedGoogleToken> {
        val response = runCatching {
            httpClient.post(config.tokenUrl) {
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("client_id", config.clientId)
                            if (config.clientSecret.isNotBlank()) {
                                append("client_secret", config.clientSecret)
                            }
                            append("refresh_token", refreshToken)
                            append("grant_type", "refresh_token")
                        },
                    ),
                )
            }
        }.getOrElse { exception ->
            exception.rethrowIfCancellation()
            return refreshRequestError(exception)
        }
        if (!response.status.isSuccess()) {
            val rejected = if (response.status == HttpStatusCode.BadRequest) {
                try {
                    json.decodeFromString<TokenErrorResponse>(response.bodyAsText()).error == INVALID_GRANT
                } catch (exception: Throwable) {
                    exception.rethrowIfCancellation()
                    return refreshRequestError(exception)
                }
            } else {
                false
            }
            return if (rejected) invalidRefreshToken() else oauthError()
        }
        val token = runCatching {
            json.decodeFromString<RefreshTokenResponse>(response.bodyAsText())
        }.getOrElse { exception ->
            exception.rethrowIfCancellation()
            return refreshRequestError(exception)
        }
        return RefreshedGoogleToken(
            accessToken = token.accessToken,
            expiresAt = clock.now() + token.expiresIn.seconds,
        ).right()
    }

    @Suppress("ReturnCount")
    private suspend fun verifyIdToken(idToken: String): Either<UserError, GoogleIdentity> {
        val decoded = runCatching { JWT.decode(idToken) }.getOrNull() ?: return invalidGoogleToken()
        val keyId = decoded.keyId ?: return invalidGoogleToken()
        val jwks = loadJwks().fold({ return it.left() }, { it })
        val publicKey = jwks.keys[keyId] ?: return invalidGoogleToken()
        val verifier = runCatching {
            JWT.require(Algorithm.RSA256(publicKey, null))
                .build()
        }.getOrNull() ?: return invalidGoogleToken()
        val verified = runCatching { verifier.verify(idToken) }.getOrNull() ?: return invalidGoogleToken()

        if (verified.audience?.contains(config.clientId) != true) return invalidGoogleToken()
        if (verified.issuer !in allowedIssuers) return invalidGoogleToken()
        if (verified.getClaim("email_verified").asBoolean() != true) return invalidGoogleToken()

        val subject = verified.subject?.takeIf(String::isNotBlank) ?: return invalidGoogleToken()
        val email = verified.getClaim("email").asString()?.takeIf(String::isNotBlank) ?: return invalidGoogleToken()
        return GoogleIdentity(
            googleSubject = subject,
            email = email,
        ).right()
    }

    @Suppress("ReturnCount")
    private suspend fun loadJwks(): Either<UserError.ExternalError, CachedJwks> {
        val cached = cachedJwks
        if (cached != null && clock.now() < cached.expiresAt) return cached.right()

        val response = runCatching { httpClient.get(config.jwksUrl) }.getOrElse { exception ->
            exception.rethrowIfCancellation()
            return oauthError()
        }
        if (!response.status.isSuccess()) return oauthError()
        val payload = runCatching {
            json.decodeFromString<JwksResponse>(response.bodyAsText())
        }.getOrElse { exception ->
            exception.rethrowIfCancellation()
            return oauthError()
        }
        val keys = runCatching {
            payload.keys.associate { it.kid to rsaPublicKey(it.n, it.e) }
        }.getOrElse { exception ->
            exception.rethrowIfCancellation()
            return oauthError()
        }
        val jwks = CachedJwks(
            keys = keys,
            expiresAt = clock.now() + parseMaxAge(response.headers[HttpHeaders.CacheControl]),
        )
        cachedJwks = jwks
        return jwks.right()
    }

    private fun parseMaxAge(cacheControl: String?): Duration {
        val seconds = cacheControl
            ?.split(",")
            ?.map(String::trim)
            ?.firstNotNullOfOrNull { directive ->
                directive.removePrefix("max-age=").takeIf { it != directive }?.toLongOrNull()
            }
            ?: 0L
        return seconds.seconds
    }

    private fun rsaPublicKey(modulus: String, exponent: String): RSAPublicKey {
        val decoder = Base64.getUrlDecoder()
        val keySpec = RSAPublicKeySpec(
            BigInteger(1, decoder.decode(modulus)),
            BigInteger(1, decoder.decode(exponent)),
        )
        return KeyFactory.getInstance("RSA").generatePublic(keySpec) as RSAPublicKey
    }

    private fun <T> oauthError(): Either<UserError.ExternalError, T> =
        UserError.ExternalError.GoogleOAuthError.left()

    private fun <T> invalidGoogleToken(): Either<UserError, T> =
        UserError.AuthenticationError.InvalidGoogleToken.left()

    private fun Throwable.rethrowIfCancellation() {
        if (this is CancellationException) throw this
    }
}

private fun <T> invalidRefreshToken(): Either<UserError, T> =
    UserError.AuthenticationError.InvalidRefreshToken.left()

private fun <T> refreshRequestError(exception: Throwable): Either<UserError, T> =
    when (exception) {
        is SocketTimeoutException,
        is ConnectTimeoutException,
        is HttpRequestTimeoutException,
        -> UserError.ExternalError.GoogleCalendarTimeoutError.left()
        else -> UserError.ExternalError.GoogleOAuthError.left()
    }

private data class CachedJwks(
    val keys: Map<String, RSAPublicKey>,
    val expiresAt: Instant,
)

@Serializable
private data class AuthorizationTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("refresh_token")
    val refreshToken: String? = null,
    @SerialName("expires_in")
    val expiresIn: Long,
    val scope: String,
    @SerialName("id_token")
    val idToken: String,
)

@Serializable
private data class RefreshTokenResponse(
    @SerialName("access_token")
    val accessToken: String,
    @SerialName("expires_in")
    val expiresIn: Long,
)

@Serializable
private data class TokenErrorResponse(
    val error: String,
)

@Serializable
private data class JwksResponse(
    val keys: List<JwkKey>,
)

@Serializable
private data class JwkKey(
    val kid: String,
    val n: String,
    val e: String,
)

private const val INVALID_GRANT = "invalid_grant"
