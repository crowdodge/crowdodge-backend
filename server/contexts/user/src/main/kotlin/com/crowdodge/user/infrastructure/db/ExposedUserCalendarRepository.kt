package com.crowdodge.user.infrastructure.db

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.either
import arrow.core.right
import com.crowdodge.shared.infra.db.PostgresSqlState
import com.crowdodge.shared.kernel.PersistedDataCorruption
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import io.r2dbc.spi.R2dbcDataIntegrityViolationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.ExposedR2dbcException
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll

/**
 * [UserCalendarRepository] の Exposed(R2DBC) 実装。
 * トランザクションは UseCase が TransactionRunner で開き、本実装はその tx に参加する（§11）。
 */
class ExposedUserCalendarRepository : UserCalendarRepository {

    override suspend fun create(userCalendar: UserCalendar): Either<UserError.ConflictError.DuplicateCalendar, Unit> =
        either {
            onDuplicateCalendar {
                UserCalendarsTable.insert {
                    it[userCalendarUuid] = userCalendar.userCalendarUuid.value
                    it[userUuid] = userCalendar.userUuid.value
                    it[googleCalendarId] = userCalendar.googleCalendarId.value
                }
            }
        }

    override suspend fun delete(userUuid: UserUuid, userCalendarUuid: UserCalendarUuid) {
        // 所有者スコープ付き削除（他ユーザーのカレンダーは消せない）。
        UserCalendarsTable.deleteWhere {
            (UserCalendarsTable.userCalendarUuid eq userCalendarUuid.value) and
                (UserCalendarsTable.userUuid eq userUuid.value)
        }
    }

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserCalendar> =
        UserCalendarsTable.selectAll()
            .where { UserCalendarsTable.userUuid eq userUuid.value }
            .map { toDomain(it) }
            .toList()

    override suspend fun findAll(): List<UserCalendar> =
        UserCalendarsTable.selectAll()
            .map { toDomain(it) }
            .toList()

    override suspend fun replaceForUser(
        userUuid: UserUuid,
        calendars: List<UserCalendar>,
    ): Either<UserError.ConflictError.DuplicateCalendar, Unit> {
        UserCalendarsTable.deleteWhere { UserCalendarsTable.userUuid eq userUuid.value }
        calendars.forEach {
            val result = create(it)
            if (result.isLeft()) return result
        }
        return Unit.right()
    }

    /**
     * 一意制約違反（SQLSTATE 23505 = UNIQUE(user_uuid, google_calendar_id)）だけを
     * [UserError.ConflictError.DuplicateCalendar] に変換する。FK 等の他の整合性違反や接続断は例外のまま透過（5xx）。
     */
    private suspend fun Raise<UserError.ConflictError.DuplicateCalendar>.onDuplicateCalendar(
        block: suspend () -> Unit,
    ) = catch({ block() }) { e: ExposedR2dbcException ->
        val integrityError = e.cause as? R2dbcDataIntegrityViolationException
        if (integrityError?.sqlState == PostgresSqlState.UNIQUE_VIOLATION) {
            raise(UserError.ConflictError.DuplicateCalendar)
        } else {
            throw e
        }
    }

    /** DB 行をドメインへ復元する。検証を通らなければ永続データ破損として扱う（5xx）。 */
    private fun toDomain(row: ResultRow): UserCalendar =
        either {
            UserCalendar.reconstitute(
                userCalendarUuid = UserCalendarUuid(row[UserCalendarsTable.userCalendarUuid]),
                userUuid = UserUuid(row[UserCalendarsTable.userUuid]),
                googleCalendarId = googleCalendarId(row[UserCalendarsTable.googleCalendarId]),
            )
        }.getOrElse { throw PersistedDataCorruption("UserCalendar の復元に失敗しました: ${it.code}") }
}
