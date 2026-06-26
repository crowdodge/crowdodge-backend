package com.crowdodge.event.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import com.crowdodge.shared.infra.db.instantTimestampWithTimeZone

object EventCalendarSyncsTable : TimestampedTable("event_calendar_syncs") {
    // per-user 同期の連携状態。1 user_calendar = 1 watch チャネル + 1 syncToken。
    // 参照: user_calendars.user_calendar_uuid（別 BC を値参照する ACL。cross-BC の物理 FK は張らない）。
    val userCalendarUuid = uuid("user_calendar_uuid")

    // 増分同期の継続トークン（events.list の nextSyncToken）。410 無効化時は null に戻しフル再同期。
    val syncToken = text("sync_token").nullable()

    // 投影済みローリング窓の地平線（この時刻まで取り込み済み）。
    val materializedUntil = instantTimestampWithTimeZone("materialized_until").nullable()

    // webhook 受信時の逆引きキー（X-Goog-Channel-ID）。
    val watchChannelId = text("watch_channel_id").nullable().uniqueIndex()
    val watchResourceId = text("watch_resource_id").nullable()
    val watchChannelToken = text("watch_channel_token").nullable()

    // 更新対象（期限切れ間近）抽出用にインデックス。watch は最大 ~7 日で失効するため随時更新。
    val watchExpiration = instantTimestampWithTimeZone("watch_expiration").nullable().index()
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(userCalendarUuid)
}
