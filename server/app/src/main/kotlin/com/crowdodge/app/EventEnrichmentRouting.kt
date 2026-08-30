package com.crowdodge.app

import com.crowdodge.event.application.query.ListEventEnrichmentsUseCase
import com.crowdodge.event.presentation.eventEnrichmentRoutes
import com.crowdodge.user.presentation.APP_JWT_AUTH_NAME
import com.crowdodge.user.presentation.AuthenticatedUserPrincipal
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

fun Application.configureEventEnrichmentRouting() {
    val useCase by inject<ListEventEnrichmentsUseCase>()

    routing {
        route("/v1") {
            authenticate(APP_JWT_AUTH_NAME) {
                eventEnrichmentRoutes(useCase) {
                    principal<AuthenticatedUserPrincipal>()?.userUuid
                }
            }
        }
    }
}
