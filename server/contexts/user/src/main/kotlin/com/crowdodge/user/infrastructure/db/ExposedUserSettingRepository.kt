package com.crowdodge.user.infrastructure.db

import arrow.core.getOrElse
import arrow.core.raise.either
import com.crowdodge.shared.kernel.PersistedDataCorruption
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.RemindTiming.Companion.remindTiming
import com.crowdodge.user.domain.model.UserSetting
import com.crowdodge.user.domain.repository.UserSettingRepository
import com.crowdodge.user.infrastructure.persistence.UserSettingsTable
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert

/**
 * [UserSettingRepository] の Exposed(R2DBC) 実装。
 * userUuid をキーに upsert（1:1）。トランザクションは UseCase が開いた tx に参加する（§11）。
 */
class ExposedUserSettingRepository : UserSettingRepository {

    override suspend fun save(userSetting: UserSetting) {
        UserSettingsTable.upsert {
            it[userUuid] = userSetting.userUuid.value
            it[home] = userSetting.home
            it[remindTiming] = userSetting.remindTiming.duration
        }
    }

    override suspend fun findByUserUuid(userUuid: UserUuid): UserSetting? =
        UserSettingsTable.selectAll()
            .where { UserSettingsTable.userUuid eq userUuid.value }
            .firstOrNull()
            ?.let { toDomain(it) }

    /** DB 行をドメインへ復元する。検証を通らなければ永続データ破損として扱う（5xx）。 */
    private fun toDomain(row: ResultRow): UserSetting =
        either {
            UserSetting.reconstitute(
                userUuid = UserUuid(row[UserSettingsTable.userUuid]),
                home = row[UserSettingsTable.home],
                remindTiming = remindTiming(row[UserSettingsTable.remindTiming]),
            )
        }.getOrElse { throw PersistedDataCorruption("UserSetting の復元に失敗しました: ${it.code}") }
}
