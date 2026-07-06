package com.crowdodge.user.presentation

import com.crowdodge.shared.infra.web.Problem
import io.ktor.http.HttpStatusCode

internal fun proxyProblemFor(status: Int): Problem? =
    when (status) {
        HttpStatusCode.Unauthorized.value -> Problem(
            status = HttpStatusCode.Unauthorized.value,
            code = "GOOGLE_REAUTH_REQUIRED",
            title = "Unauthorized",
            detail = "Google の再認可が必要です",
        )
        HttpStatusCode.BadGateway.value -> Problem(
            status = HttpStatusCode.BadGateway.value,
            code = "GOOGLE_CALENDAR_ERROR",
            title = "Bad Gateway",
            detail = "Google Calendar request failed",
        )
        HttpStatusCode.GatewayTimeout.value -> Problem(
            status = HttpStatusCode.GatewayTimeout.value,
            code = "GOOGLE_CALENDAR_TIMEOUT",
            title = "Gateway Timeout",
            detail = "Google Calendar request timed out",
        )
        in SANITIZED_GOOGLE_ERROR_STATUSES -> Problem(
            status = status,
            code = "GOOGLE_CALENDAR_ERROR",
            title = HttpStatusCode.fromValue(status).description,
            detail = "Google Calendar request failed",
        )
        else -> null
    }

private val SANITIZED_GOOGLE_ERROR_STATUSES = setOf(
    HttpStatusCode.Forbidden.value,
    HttpStatusCode.NotFound.value,
    HttpStatusCode.Conflict.value,
    HttpStatusCode.Gone.value,
    HttpStatusCode.TooManyRequests.value,
)
