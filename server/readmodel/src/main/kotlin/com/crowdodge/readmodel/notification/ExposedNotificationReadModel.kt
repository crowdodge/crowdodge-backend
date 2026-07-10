package com.crowdodge.readmodel.notification

import com.crowdodge.event.infrastructure.persistence.EventsTable
import com.crowdodge.notification.application.port.DispatchReadModelPort
import com.crowdodge.notification.application.port.EventDispatchSource
import com.crowdodge.notification.application.port.EventRegistrationSource
import com.crowdodge.notification.application.port.RegistrationReadModelPort
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.shared.kernel.AppTime
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import com.crowdodge.user.infrastructure.persistence.UserDevicesTable
import com.crowdodge.user.infrastructure.persistence.UserSettingsTable
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import kotlin.time.Instant

/**
 * notification BC 向けの BC 横断読み取り（dispatch / 登録）の Exposed(R2DBC) 実装。
 * 他 BC の Table 定義を読み取り専用で参照し、バルク IN 句の SELECT で必要な情報を集める。
 * 各メソッドは外側にトランザクションがあれば参加し、無ければ自ら readOnly を開く（join-or-begin）。
 */
class ExposedNotificationReadModel(
    private val transactions: TransactionRunner,
) : DispatchReadModelPort, RegistrationReadModelPort {

    override suspend fun eventSources(eventUuids: List<EventUuid>): Map<EventUuid, EventDispatchSource> {
        if (eventUuids.isEmpty()) return emptyMap()
        return transactions.readOnly {
            EventsTable.selectAll()
                .where { EventsTable.eventUuid inList eventUuids.map { it.value } }
                .mapNotNull { toDispatchSource(it) }
                .toList()
                .associateBy { it.eventUuid }
        }
    }

    override suspend fun fcmTokens(userUuids: List<UserUuid>): Map<UserUuid, List<String>> {
        if (userUuids.isEmpty()) return emptyMap()
        return transactions.readOnly {
            UserDevicesTable.selectAll()
                .where { UserDevicesTable.userUuid inList userUuids.map { it.value } }
                .map { row -> UserUuid(row[UserDevicesTable.userUuid]) to row[UserDevicesTable.fcmToken] }
                .toList()
                .groupBy({ it.first }, { it.second })
        }
    }

    /**
     * owner（user_calendars）は INNER JOIN で解決し、行が無い予定は結果から脱落＝登録スキップ。
     * user 既定リマインド（user_settings）は LEFT JOIN で、行が無ければ getOrNull で null。
     */
    override suspend fun registrationSources(
        eventUuids: List<EventUuid>,
    ): Map<EventUuid, EventRegistrationSource> {
        if (eventUuids.isEmpty()) return emptyMap()
        return transactions.readOnly {
            EventsTable
                .join(
                    UserCalendarsTable,
                    JoinType.INNER,
                    onColumn = EventsTable.userCalendarUuid,
                    otherColumn = UserCalendarsTable.userCalendarUuid,
                )
                .join(
                    UserSettingsTable,
                    JoinType.LEFT,
                    onColumn = UserCalendarsTable.userUuid,
                    otherColumn = UserSettingsTable.userUuid,
                )
                .select(
                    EventsTable.eventUuid,
                    EventsTable.startTime,
                    EventsTable.startDate,
                    EventsTable.remindTiming,
                    UserCalendarsTable.userUuid,
                    UserSettingsTable.remindTiming,
                )
                .where { EventsTable.eventUuid inList eventUuids.map { it.value } }
                .mapNotNull { toRegistrationSource(it) }
                .toList()
                .associateBy { it.eventUuid }
        }
    }

    private fun toDispatchSource(row: ResultRow): EventDispatchSource? {
        val (start, isAllDay) = resolveStart(row) ?: return null
        return EventDispatchSource(
            eventUuid = EventUuid(row[EventsTable.eventUuid]),
            title = row[EventsTable.title],
            start = start,
            isAllDay = isAllDay,
        )
    }

    private fun toRegistrationSource(row: ResultRow): EventRegistrationSource? {
        val (start, _) = resolveStart(row) ?: return null
        return EventRegistrationSource(
            eventUuid = EventUuid(row[EventsTable.eventUuid]),
            userUuid = UserUuid(row[UserCalendarsTable.userUuid]),
            start = start,
            remindTiming = row[EventsTable.remindTiming],
            defaultRemindTiming = row.getOrNull(UserSettingsTable.remindTiming),
        )
    }

    /** start_time / start_date のどちらも欠けた不正行は null（予定なし扱い）。終日は当日 0:00 JST を Instant 化。 */
    private fun resolveStart(row: ResultRow): Pair<Instant, Boolean>? {
        val startTime = row[EventsTable.startTime]
        val startDate = row[EventsTable.startDate]
        return when {
            startTime != null -> startTime to false
            startDate != null -> AppTime.startOfBusinessDate(startDate) to true
            else -> null
        }
    }
}
