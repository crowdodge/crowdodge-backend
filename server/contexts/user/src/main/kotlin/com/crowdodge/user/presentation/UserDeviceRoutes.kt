package com.crowdodge.user.presentation

import com.crowdodge.shared.infra.web.respondProblem
import com.crowdodge.user.application.command.RegisterUserDeviceCommand
import com.crowdodge.user.application.command.RegisterUserDeviceUseCase
import com.crowdodge.user.presentation.problem.toProblem
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable

fun Route.userDeviceRoutes(
    registerUserDeviceUseCase: RegisterUserDeviceUseCase,
) {
    post("/devices") {
        val principal = call.authenticatedUserOrProblem() ?: return@post
        val request = call.receiveOrInvalidRequestProblem<RegisterDeviceRequest>() ?: return@post

        registerUserDeviceUseCase.handle(
            RegisterUserDeviceCommand(
                userUuid = principal.userUuid,
                fcmToken = request.fcmToken,
            ),
        ).fold(
            ifLeft = { error -> call.respondProblem(error.toProblem()) },
            ifRight = { call.respond(HttpStatusCode.NoContent) },
        )
    }
}

@Serializable
data class RegisterDeviceRequest(
    val fcmToken: String,
)
