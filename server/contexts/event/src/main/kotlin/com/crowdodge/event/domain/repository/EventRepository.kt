package com.crowdodge.event.domain.repository

import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.UserCalendarUuid

/**
 * Event 集約の永続化ポート。実装は infrastructure、トランザクション境界は application（§11）。
 *
 * events は Google カレンダーの投影専用（ユーザーが直接 CRUD しない）。増分同期はまとめて届くため、
 * 書き込みは1件ずつではなくバッチで反映する。
 */
interface EventRepository {
    /**
     * 増分同期で取得した予定をまとめて反映する（(user_calendar_uuid, google_event_id) で冪等 upsert）。
     * 既存行の自社 [EventUuid]・created_at は保持し、投影内容のみ更新する（下流が参照する [EventUuid] を安定させる）。
     */
    suspend fun upsertAll(events: List<Event>)

    /** Google 側削除（増分同期の `status=cancelled`）分をまとめて除去する。 */
    suspend fun deleteByGoogleEventIds(userCalendarUuid: UserCalendarUuid, googleEventIds: List<GoogleEventId>)

    /** ローリング窓外への退避（自社 [EventUuid] 基準の prune）。 */
    suspend fun delete(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid)

    suspend fun findByEventUuid(userCalendarUuid: UserCalendarUuid, eventUuid: EventUuid): Event?

    /**
     * 増分同期の差分検知用に、[googleEventIds] 群の既存投影を一括で引く（per-row 照合の往復を避ける）。
     * 新規/更新の判定と、更新時の既存 [EventUuid] 保持に使う。
     */
    suspend fun findByGoogleEventIds(
        userCalendarUuid: UserCalendarUuid,
        googleEventIds: List<GoogleEventId>,
    ): List<Event>

    /**
     * フル再同期の整合（prune）用に、当該カレンダーの保存済み投影を全件引く。
     * 窓内に限定して保持する運用のため件数は有界。
     */
    suspend fun findAllByUserCalendarUuid(userCalendarUuid: UserCalendarUuid): List<Event>
}
