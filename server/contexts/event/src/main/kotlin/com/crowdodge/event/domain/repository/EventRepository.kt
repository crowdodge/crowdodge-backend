package com.crowdodge.event.domain.repository

import arrow.core.Either
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.Event
import com.crowdodge.event.domain.model.EventUuid
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.UserCalendarUuid

/**
 * Event 集約の永続化ポート。実装は infrastructure、トランザクション境界は application（§11）。
 */
interface EventRepository {
    /**
     * 新規投影（insert）。google_event_id の一意制約違反は
     * [EventError.ConflictError.DuplicateGoogleEvent] を返す（DB 制約が番人。並行時も安全）。
     */
    suspend fun create(event: Event): Either<EventError.ConflictError.DuplicateGoogleEvent, Unit>

    /**
     * 既存投影の更新（event_uuid 一致行を update）。他行と同じ google_event_id への変更は
     * [EventError.ConflictError.DuplicateGoogleEvent] を返す（不在時は 0 行＝no-op）。
     */
    suspend fun update(event: Event): Either<EventError.ConflictError.DuplicateGoogleEvent, Unit>

    /** ローリング窓外への退避・Google 側削除に伴う除去。 */
    suspend fun delete(eventUuid: EventUuid)

    suspend fun findByEventUuid(eventUuid: EventUuid): Event?

    /** Google 同期の突合に使う（events.google_event_id は突合キー）。 */
    suspend fun findByGoogleEventId(googleEventId: GoogleEventId): Event?

    /** 当該カレンダー由来の投影済み予定一覧。 */
    suspend fun findByUserCalendarUuid(userCalendarUuid: UserCalendarUuid): List<Event>
}
