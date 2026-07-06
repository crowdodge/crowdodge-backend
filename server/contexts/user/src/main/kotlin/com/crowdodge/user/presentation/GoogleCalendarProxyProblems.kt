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
        in HTTP_ERROR_STATUS_RANGE -> Problem(
            status = status,
            code = "GOOGLE_CALENDAR_ERROR",
            title = HttpStatusCode.fromValue(status).description,
            detail = "Google Calendar request failed",
        )
        else -> null
    }

private val HTTP_ERROR_STATUS_RANGE = MIN_HTTP_ERROR_STATUS..MAX_HTTP_ERROR_STATUS

private const val MIN_HTTP_ERROR_STATUS = 400
private const val MAX_HTTP_ERROR_STATUS = 599
