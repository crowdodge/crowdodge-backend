package com.crowdodge.user.presentation

import com.crowdodge.shared.infra.web.Problem
import com.crowdodge.shared.infra.web.respondProblem
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.command.AuthenticateWithGoogleCommand
import com.crowdodge.user.application.command.AuthenticateWithGoogleUseCase
import com.crowdodge.user.application.command.LogoutCommand
import com.crowdodge.user.application.command.LogoutUseCase
import com.crowdodge.user.application.command.RefreshSessionCommand
import com.crowdodge.user.application.command.RefreshSessionUseCase
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.domain.error.UserError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

const val APP_JWT_AUTH_NAME = "app-jwt"

data class AuthenticatedUserPrincipal(val userUuid: UserUuid)

@Suppress("LongMethod")
fun Application.configureUserRouting() {
    val authenticateWithGoogleUseCase by inject<AuthenticateWithGoogleUseCase>()
    val refreshSessionUseCase by inject<RefreshSessionUseCase>()
    val logoutUseCase by inject<LogoutUseCase>()
    val jwtConfig by inject<JwtAppTokenConfig>()

    routing {
        route("/auth") {
            post("/google") {
                val request = call.receiveOrProblem<GoogleLoginRequest>() ?: return@post
                val violations = buildList {
                    addIfInvalid("authorizationCode", request.authorizationCode)
                    addIfInvalid("redirectUri", request.redirectUri)
                    addIfInvalid("codeVerifier", request.codeVerifier)
                }
                if (violations.isNotEmpty()) {
                    return@post call.respondProblem(validationProblem(violations))
                }

                authenticateWithGoogleUseCase.handle(
                    AuthenticateWithGoogleCommand(
                        authorizationCode = request.authorizationCode.trim(),
                        redirectUri = request.redirectUri.trim(),
                        codeVerifier = request.codeVerifier.trim(),
                    ),
                ).fold(
                    ifLeft = { call.respondProblem(it.toProblem()) },
                    ifRight = { result ->
                        call.respond(
                            HttpStatusCode.OK,
                            TokenResponse(
                                accessToken = result.accessToken,
                                refreshToken = result.refreshToken,
                                expiresIn = jwtConfig.accessTokenTtl.inWholeSeconds,
                            ),
                        )
                    },
                )
            }

            post("/refresh") {
                val request = call.receiveOrProblem<RefreshTokenRequest>() ?: return@post
                val violations = buildList {
                    addIfInvalid("refreshToken", request.refreshToken, maxLength = REFRESH_TOKEN_MAX_LENGTH)
                }
                if (violations.isNotEmpty()) {
                    return@post call.respondProblem(validationProblem(violations))
                }

                refreshSessionUseCase.handle(
                    RefreshSessionCommand(refreshToken = request.refreshToken.trim()),
                ).fold(
                    ifLeft = { call.respondProblem(it.toProblem()) },
                    ifRight = { result ->
                        call.respond(
                            HttpStatusCode.OK,
                            TokenResponse(
                                accessToken = result.accessToken,
                                refreshToken = result.refreshToken,
                                expiresIn = jwtConfig.accessTokenTtl.inWholeSeconds,
                            ),
                        )
                    },
                )
            }

            post("/logout") {
                val request = call.receiveOrProblem<RefreshTokenRequest>() ?: return@post
                val violations = buildList {
                    addIfInvalid("refreshToken", request.refreshToken, maxLength = REFRESH_TOKEN_MAX_LENGTH)
                }
                if (violations.isNotEmpty()) {
                    return@post call.respondProblem(validationProblem(violations))
                }

                logoutUseCase.handle(
                    LogoutCommand(refreshToken = request.refreshToken.trim()),
                ).fold(
                    ifLeft = { call.respondProblem(it.toProblem()) },
                    ifRight = { call.respond(HttpStatusCode.NoContent) },
                )
            }

            authenticate(APP_JWT_AUTH_NAME) {
                get("/me") {
                    val principal = call.principal<AuthenticatedUserPrincipal>()
                        ?: return@get call.respondProblem(
                            Problem(
                                status = 401,
                                type = "unauthorized",
                                title = "Unauthorized",
                                detail = "認証が必要です",
                            ),
                        )

                    call.respond(CurrentUserResponse(userUuid = principal.userUuid.value.toString()))
                }
            }
        }
    }
}

@Serializable
data class GoogleLoginRequest(
    val authorizationCode: String,
    val redirectUri: String,
    val codeVerifier: String,
)

@Serializable
data class RefreshTokenRequest(
    val refreshToken: String,
)

@Serializable
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val tokenType: String = "Bearer",
    val expiresIn: Long,
)

@Serializable
data class CurrentUserResponse(
    val userUuid: String,
)

private suspend inline fun <reified T : Any> io.ktor.server.application.ApplicationCall.receiveOrProblem(): T? =
    runCatching { receive<T>() }
        .getOrElse {
            respondProblem(
                Problem(
                    status = 400,
                    type = "invalid-request",
                    title = "Bad Request",
                    detail = "リクエスト形式が不正です",
                ),
            )
            null
        }

private fun UserError.toProblem(): Problem = when (this) {
    is UserError.ValidationError -> validationProblem(
        listOf(Problem.Violation(field = name, message = code)),
    )

    is UserError.ConflictError -> Problem(
        status = 409,
        type = code,
        title = "Conflict",
        detail = "競合するリソースが存在します",
    )

    is UserError.AuthenticationError.InvalidGoogleToken -> Problem(
        status = 401,
        type = code,
        title = "Unauthorized",
        detail = "Google 認証に失敗しました",
    )

    is UserError.AuthenticationError.InvalidRefreshToken -> Problem(
        status = 401,
        type = code,
        title = "Unauthorized",
        detail = "refresh token が無効です",
    )

    is UserError.AuthenticationError.MissingGoogleScope -> Problem(
        status = 403,
        type = code,
        title = "Forbidden",
        detail = "必要な Google scope が不足しています",
    )

    is UserError.ExternalError.GoogleOAuthError -> Problem(
        status = 502,
        type = code,
        title = "Bad Gateway",
        detail = "Google OAuth 連携に失敗しました",
    )
}

private fun validationProblem(violations: List<Problem.Violation>): Problem =
    Problem(
        status = 400,
        type = "validation-error",
        title = "Bad Request",
        detail = "入力値が不正です",
        violations = violations,
    )

private fun MutableList<Problem.Violation>.addIfInvalid(
    field: String,
    value: String,
    maxLength: Int = DEFAULT_MAX_LENGTH,
) {
    val trimmed = value.trim()
    when {
        trimmed.isEmpty() -> add(Problem.Violation(field = field, message = "must-not-be-blank"))
        trimmed.length > maxLength -> add(
            Problem.Violation(field = field, message = "must-be-at-most-$maxLength-chars")
        )
    }
}

private const val DEFAULT_MAX_LENGTH = 2048
private const val REFRESH_TOKEN_MAX_LENGTH = 4096
