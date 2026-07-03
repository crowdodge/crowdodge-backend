package com.crowdodge.app.plugins

import com.auth0.jwt.JWT
import com.auth0.jwt.JWTVerifier
import com.crowdodge.shared.infra.web.Problem
import com.crowdodge.shared.infra.web.respondProblem
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.infrastructure.security.hmacAlgorithm
import com.crowdodge.user.presentation.APP_JWT_AUTH_NAME
import com.crowdodge.user.presentation.AuthenticatedUserPrincipal
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.jwt.jwt
import org.koin.ktor.ext.inject
import kotlin.uuid.Uuid
import java.time.Clock as JavaClock

fun Application.configureAuthentication(jwtClock: JavaClock = JavaClock.systemUTC()) {
    val jwtConfig by inject<JwtAppTokenConfig>()
    val verification = JWT.require(jwtConfig.hmacAlgorithm())
        .withIssuer(jwtConfig.issuer)
        .withAudience(jwtConfig.audience)
    val verifier = (verification as JWTVerifier.BaseVerification).build(jwtClock)

    install(Authentication) {
        jwt(APP_JWT_AUTH_NAME) {
            this.verifier(verifier)
            validate { credential ->
                val subject = credential.payload.subject ?: return@validate null
                val userUuid = runCatching { UserUuid(Uuid.parse(subject)) }.getOrNull() ?: return@validate null
                AuthenticatedUserPrincipal(userUuid)
            }
            challenge { _, _ ->
                call.respondProblem(
                    Problem(
                        status = 401,
                        type = "unauthorized",
                        title = "Unauthorized",
                        detail = "認証が必要です",
                    ),
                )
            }
        }
    }
}
