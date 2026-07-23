package com.crowdodge.app

import com.crowdodge.user.application.command.RegisterUserDeviceUseCase
import com.crowdodge.user.presentation.APP_JWT_AUTH_NAME
import com.crowdodge.user.presentation.userDeviceRoutes
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

fun Application.configureApplicationRouting() {
    val registerUserDeviceUseCase by inject<RegisterUserDeviceUseCase>()

    routing {
        route("/v1") {
            authenticate(APP_JWT_AUTH_NAME) {
                userDeviceRoutes(registerUserDeviceUseCase)
            }
        }
    }
}
