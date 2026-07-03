package com.crowdodge.event.presentation

import arrow.core.getOrElse
import com.crowdodge.event.application.command.HandleGoogleCalendarWebhookUseCase
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import org.koin.ktor.ext.inject

fun Application.configureEventRouting() {
    val handleGoogleCalendarWebhookUseCase by inject<HandleGoogleCalendarWebhookUseCase>()

    routing {
        post("/webhooks/google-calendar") {
            val channelId = call.request.headers[HEADER_CHANNEL_ID]
                ?: return@post call.respond(HttpStatusCode.BadRequest)
            val channelToken = call.request.headers[HEADER_CHANNEL_TOKEN]
            val resourceState = call.request.headers[HEADER_RESOURCE_STATE]
                ?: return@post call.respond(HttpStatusCode.BadRequest)

            handleGoogleCalendarWebhookUseCase.execute(
                channelId = channelId,
                channelToken = channelToken,
                resourceState = resourceState,
            ).getOrElse {
                return@post call.respond(HttpStatusCode.BadGateway)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

private const val HEADER_CHANNEL_ID = "X-Goog-Channel-ID"
private const val HEADER_CHANNEL_TOKEN = "X-Goog-Channel-Token"
private const val HEADER_RESOURCE_STATE = "X-Goog-Resource-State"
