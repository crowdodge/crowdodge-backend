package com.crowdodge.event.infrastructure.db.datasource

import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.infrastructure.db.model.EventCalendarSync
import com.crowdodge.event.infrastructure.persistence.EventCalendarSyncsTable
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import kotlin.time.Instant

/**
 * [EventCalendarSync]（連携状態）の Exposed(R2DBC) 永続化。domain repository IF は持たない
 * （連携機構の状態であり domain に属さないため）。トランザクションは呼び出し側が貼る。
 */
class ExposedEventCalendarSyncDataSource {
    /**
     * user_calendar_uuid（PK）で upsert。created_at は更新時にクロバーしない（updated_at は clientDefault で更新）。
     */
    suspend fun upsert(sync: EventCalendarSync) {
        EventCalendarSyncsTable.upsert(
            onUpdateExclude = listOf(EventCalendarSyncsTable.createdAt),
        ) { toModel(sync)(it) }
    }

    /** webhook 受信時の逆引き（X-Goog-Channel-ID = watch_channel_id）。 */
    suspend fun findByWatchChannelId(watchChannelId: String): EventCalendarSync? =
        EventCalendarSyncsTable
            .selectAll()
            .where { EventCalendarSyncsTable.watchChannelId eq watchChannelId }
            .firstOrNull()
            ?.let { toModel(it) }

    suspend fun findByUserCalendarUuid(userCalendarUuid: UserCalendarUuid): EventCalendarSync? =
        EventCalendarSyncsTable
            .selectAll()
            .where { EventCalendarSyncsTable.userCalendarUuid eq userCalendarUuid.value }
            .firstOrNull()
            ?.let { toModel(it) }

    /** syncToken だけを引く（連携進捗の読み取り）。 */
    suspend fun findSyncToken(userCalendarUuid: UserCalendarUuid): String? =
        EventCalendarSyncsTable
            .select(EventCalendarSyncsTable.syncToken)
            .where { EventCalendarSyncsTable.userCalendarUuid eq userCalendarUuid.value }
            .firstOrNull()
            ?.let { it[EventCalendarSyncsTable.syncToken] }

    /**
     * syncToken だけを前進させる。watch 系・materialized_until など他の連携列は read-modify-write せず、
     * 競合時の UPDATE 対象から除外して触らない（lost update 回避・1 ステートメント）。行が無ければ挿入。
     */
    suspend fun saveSyncToken(userCalendarUuid: UserCalendarUuid, syncToken: String?) {
        EventCalendarSyncsTable.upsert(
            onUpdateExclude = listOf(
                EventCalendarSyncsTable.createdAt,
                EventCalendarSyncsTable.materializedUntil,
                EventCalendarSyncsTable.watchChannelId,
                EventCalendarSyncsTable.watchResourceId,
                EventCalendarSyncsTable.watchChannelToken,
                EventCalendarSyncsTable.watchExpiration,
            ),
        ) {
            it[EventCalendarSyncsTable.userCalendarUuid] = userCalendarUuid.value
            it[EventCalendarSyncsTable.syncToken] = syncToken
        }
    }

    suspend fun materializedUntil(userCalendarUuid: UserCalendarUuid): Instant? =
        EventCalendarSyncsTable
            .select(EventCalendarSyncsTable.materializedUntil)
            .where { EventCalendarSyncsTable.userCalendarUuid eq userCalendarUuid.value }
            .firstOrNull()
            ?.let { it[EventCalendarSyncsTable.materializedUntil] }

    suspend fun delete(userCalendarUuid: UserCalendarUuid) {
        EventCalendarSyncsTable.deleteWhere { EventCalendarSyncsTable.userCalendarUuid eq userCalendarUuid.value }
    }

    private fun toModel(sync: EventCalendarSync): (UpdateBuilder<*>) -> Unit = {
        with(EventCalendarSyncsTable) {
            it[userCalendarUuid] = sync.userCalendarUuid.value
            it[syncToken] = sync.syncToken
            it[materializedUntil] = sync.materializedUntil
            it[watchChannelId] = sync.watchChannelId
            it[watchResourceId] = sync.watchResourceId
            it[watchChannelToken] = sync.watchChannelToken
            it[watchExpiration] = sync.watchExpiration
        }
    }

    private fun toModel(row: ResultRow): EventCalendarSync =
        EventCalendarSync(
            userCalendarUuid = UserCalendarUuid(row[EventCalendarSyncsTable.userCalendarUuid]),
            syncToken = row[EventCalendarSyncsTable.syncToken],
            materializedUntil = row[EventCalendarSyncsTable.materializedUntil],
            watchChannelId = row[EventCalendarSyncsTable.watchChannelId],
            watchResourceId = row[EventCalendarSyncsTable.watchResourceId],
            watchChannelToken = row[EventCalendarSyncsTable.watchChannelToken],
            watchExpiration = row[EventCalendarSyncsTable.watchExpiration],
        )
}
