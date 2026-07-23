package com.crowdodge.user.application.port

import arrow.core.Either
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError

enum class GoogleCalendarAccessRole {
    OWNER,
    WRITER,
    READER,
}

data class GoogleCalendarListItem(
    val id: String,
    val name: String,
    val color: String?,
    val primary: Boolean,
    val accessRole: GoogleCalendarAccessRole,
)

fun interface GoogleCalendarListGateway {
    suspend fun listAll(userUuid: UserUuid): Either<UserError, List<GoogleCalendarListItem>>
}
