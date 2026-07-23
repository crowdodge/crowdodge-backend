package com.crowdodge.user.presentation.problem

import com.crowdodge.shared.infra.web.Problem
import com.crowdodge.user.domain.error.UserError

internal fun UserError.toProblem(): Problem = when (this) {
    is UserError.ValidationError -> validationProblem(
        listOf(Problem.Violation(field = name, code = code)),
    )

    is UserError.ConflictError -> Problem(
        status = 409,
        code = code,
        title = "Conflict",
        detail = "競合するリソースが存在します",
    )

    is UserError.AuthenticationError.InvalidGoogleToken -> Problem(
        status = 401,
        code = code,
        title = "Unauthorized",
        detail = "Google 認証に失敗しました",
    )

    is UserError.AuthenticationError.InvalidRefreshToken -> Problem(
        status = 401,
        code = code,
        title = "Unauthorized",
        detail = "refresh token が無効です",
    )

    is UserError.AuthenticationError.MissingGoogleScope -> Problem(
        status = 403,
        code = code,
        title = "Forbidden",
        detail = "必要な Google scope が不足しています",
    )

    is UserError.AuthorizationError.InsufficientCalendarAccess -> Problem(
        status = 403,
        code = code,
        title = "Forbidden",
        detail = "Google カレンダーの編集権限がありません",
    )

    is UserError.ExternalError.GoogleOAuthError -> Problem(
        status = 502,
        code = code,
        title = "Bad Gateway",
        detail = "Google OAuth 連携に失敗しました",
    )

    is UserError.ExternalError.GoogleCalendarTimeoutError -> Problem(
        status = 504,
        code = code,
        title = "Gateway Timeout",
        detail = "Google Calendar request timed out",
    )
}

internal fun validationProblem(violations: List<Problem.Violation>): Problem =
    Problem(
        status = 400,
        code = "VALIDATION_ERROR",
        title = "Bad Request",
        detail = "入力値が不正です",
        violations = violations,
    )

internal fun invalidRequestProblem(): Problem =
    Problem(
        status = 400,
        code = "INVALID_REQUEST",
        title = "Bad Request",
        detail = "リクエスト形式が不正です",
    )

internal fun unauthorizedProblem(): Problem =
    Problem(
        status = 401,
        code = "UNAUTHORIZED",
        title = "Unauthorized",
        detail = "認証が必要です",
    )
