package com.crowdodge.user.presentation

import com.crowdodge.shared.infra.web.respondProblem
import com.crowdodge.user.presentation.problem.invalidRequestProblem
import com.crowdodge.user.presentation.problem.unauthorizedProblem
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import kotlinx.coroutines.CancellationException

internal suspend fun ApplicationCall.authenticatedUserOrProblem(): AuthenticatedUserPrincipal? =
    principal<AuthenticatedUserPrincipal>()
        ?: run {
            respondProblem(
                unauthorizedProblem(),
            )
            null
        }

internal suspend inline fun <reified T : Any> ApplicationCall.receiveOrInvalidRequestProblem(): T? =
    runCatching { receive<T>() }
        .getOrElse { exception ->
            if (exception is CancellationException) {
                throw exception
            }
            respondProblem(
                invalidRequestProblem(),
            )
            null
        }
