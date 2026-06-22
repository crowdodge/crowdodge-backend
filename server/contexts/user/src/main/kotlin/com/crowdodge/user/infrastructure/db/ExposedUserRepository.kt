package com.crowdodge.user.infrastructure.db

import arrow.core.Either
import arrow.core.getOrElse
import arrow.core.raise.Raise
import arrow.core.raise.catch
import arrow.core.raise.either
import com.crowdodge.shared.infra.db.PostgresSqlState
import com.crowdodge.shared.kernel.PersistedDataCorruption
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.Email.Companion.email
import com.crowdodge.user.domain.model.GoogleId
import com.crowdodge.user.domain.model.GoogleId.Companion.googleId
import com.crowdodge.user.domain.model.User
import com.crowdodge.user.domain.repository.UserRepository
import com.crowdodge.user.infrastructure.persistence.UsersTable
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.ExposedR2dbcException
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update

/**
 * [UserRepository] の Exposed(R2DBC) 実装。
 * トランザクションは UseCase が TransactionRunner で開き、本実装はその tx に参加する（§11）。
 */
class ExposedUserRepository : UserRepository {

    override suspend fun create(user: User): Either<UserError.ConflictError.DuplicateEmail, Unit> =
        either {
            onDuplicateEmail {
                UsersTable.insert {
                    it[userUuid] = user.userUuid.value
                    it[email] = user.email.value
                    it[googleId] = user.googleId.value
                }
            }
        }

    override suspend fun update(user: User): Either<UserError.ConflictError.DuplicateEmail, Unit> =
        either {
            onDuplicateEmail {
                UsersTable.update({ UsersTable.userUuid eq user.userUuid.value }) {
                    it[email] = user.email.value
                    it[googleId] = user.googleId.value
                }
            }
        }

    override suspend fun findByUserUuid(userUuid: UserUuid): User? =
        UsersTable.selectAll().where { UsersTable.userUuid eq userUuid.value }.firstOrNull()
            ?.let { toDomain(it) }

    override suspend fun findByGoogleId(googleId: GoogleId): User? =
        UsersTable.selectAll().where { UsersTable.googleId eq googleId.value }.firstOrNull()
            ?.let { toDomain(it) }

    /**
     * 一意制約違反（SQLSTATE 23505）だけを [UserError.ConflictError.DuplicateEmail] に変換する。
     * FK/NOT NULL/CHECK 等の他の整合性違反や接続断は例外のまま透過（5xx）。
     * users の一意制約は email のみのため 23505＝重複メールとみなす（将来複数になれば制約名で判別）。
     */
    private suspend fun Raise<UserError.ConflictError.DuplicateEmail>.onDuplicateEmail(
        block: suspend () -> Unit,
    ) = catch({ block() }) { e: ExposedR2dbcException ->
        if (e.sqlState == PostgresSqlState.UNIQUE_VIOLATION) {
            raise(UserError.ConflictError.DuplicateEmail)
        } else {
            throw e
        }
    }

    /** DB 行をドメインへ復元する。検証を通らなければ永続データ破損として扱う（5xx）。 */
    private fun toDomain(row: ResultRow): User =
        either {
            User.reconstitute(
                userUuid = UserUuid(row[UsersTable.userUuid]),
                googleId = googleId(row[UsersTable.googleId]),
                email = email(row[UsersTable.email]),
            )
        }.getOrElse { throw PersistedDataCorruption("User の復元に失敗しました: ${it.code}") }
}
