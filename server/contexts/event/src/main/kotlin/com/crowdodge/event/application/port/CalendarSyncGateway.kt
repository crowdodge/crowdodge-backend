package com.crowdodge.event.application.port

import arrow.core.Either
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.event.domain.model.EventContent
import com.crowdodge.event.domain.model.GoogleEventId
import com.crowdodge.event.domain.model.RecurringEventId
import com.crowdodge.event.domain.model.UserCalendarUuid
import kotlin.time.Instant

/**
 * Google Calendar の増分同期ゲートウェイ（被駆動ポート）。実装は infrastructure の ACL。
 *
 * application がこのポートにのみ依存し、syncToken の受け渡しや events.list 呼び出しといった
 * 連携詳細は ACL 実装に閉じる（domain は syncToken を知らない＝連携状態を domain に出さない）。
 */
interface CalendarSyncGateway {
    /**
     * 前回の [syncToken]（無ければ null＝初回フル同期）から増分を取得する。
     *
     * 410 GONE（syncToken 失効）時は実装内でフル再同期へフォールバックし、
     * [CalendarSyncResult.isFullSync] = true で返す（呼び出し側が完全集合として prune できる）。
     * フル同期時のみ timeMin/timeMax で 3ヶ月窓を Google 側に絞れる。増分同期は syncToken と
     * timeMin/timeMax を併用できない（Google 仕様、違反は 400）ため窓外の予定も返り得る
     * → **窓フィルタは呼び出し側（ユースケース）の責務**。
     * 外部 API 呼び出しのためトランザクションの外で呼ぶ（§11）。
     */
    suspend fun fetchUpdatedEvents(
        userCalendarUuid: UserCalendarUuid,
        syncToken: String?,
    ): Either<EventError.ExternalError, CalendarSyncResult>
}

/**
 * 同期1回分の結果。[upserts] は反映（新規/更新）対象、[cancellations] は削除対象
 * （Google の `status=cancelled`）、[nextSyncToken] は次回継続トークン。
 *
 * [isFullSync] が true（初回 or 410 後のフル再同期）のとき、[upserts] は当該カレンダーの
 * 窓内**完全集合**を表す。呼び出し側は結果に現れない既存投影を整理（prune）する。
 */
data class CalendarSyncResult(
    val upserts: List<IncomingCalendarEvent>,
    val cancellations: List<GoogleEventId>,
    val nextSyncToken: String?,
    val isFullSync: Boolean,
)

/**
 * Google 由来の予定（投影前）。自社 [com.crowdodge.event.domain.model.EventUuid] は未採番で、
 * application が既存投影との差分検知で新規/更新を判定し、新規は
 * [com.crowdodge.event.domain.model.Event.schedule]（採番）、更新は
 * [com.crowdodge.event.domain.model.Event.reproject]（既存 EventUuid を保持）に振り分けて一括反映する。
 */
data class IncomingCalendarEvent(
    val googleEventId: GoogleEventId,
    val recurringEventId: RecurringEventId?,
    val originalStart: Instant?,
    val eventContent: EventContent,
)
