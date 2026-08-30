package com.crowdodge.event.application.query

import com.crowdodge.event.application.port.CalendarEventEnrichments
import com.crowdodge.event.application.port.EventEnrichmentReadModel
import com.crowdodge.shared.kernel.UserUuid
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

class ListEventEnrichmentsUseCaseTest : FunSpec({
    val userUuid = UserUuid(Uuid.random())
    val calendars = listOf(
        CalendarEventEnrichments("calendar-a", emptyList()),
        CalendarEventEnrichments("calendar-b", emptyList()),
    )

    test("calendarId省略時は選択中の全カレンダーを返す") {
        val readModel = RecordingEventEnrichmentReadModel(calendars)

        val result = ListEventEnrichmentsUseCase(readModel).handle(userUuid, null).getOrNull()!!

        result shouldContainExactly calendars
        readModel.calls shouldContainExactly listOf(userUuid to null)
    }

    test("指定したcalendarIdがすべて選択中なら対象カレンダーを返す") {
        val selected = calendars.take(1)
        val readModel = RecordingEventEnrichmentReadModel(selected)

        val result = ListEventEnrichmentsUseCase(readModel)
            .handle(userUuid, setOf("calendar-a"))
            .getOrNull()!!

        result shouldContainExactly selected
    }

    test("指定したcalendarIdに未選択カレンダーが含まれればリクエスト全体を失敗させる") {
        val readModel = RecordingEventEnrichmentReadModel(calendars.take(1))

        val error = ListEventEnrichmentsUseCase(readModel)
            .handle(userUuid, setOf("calendar-a", "calendar-x"))
            .leftOrNull()!!

        error.unavailableGoogleCalendarIds shouldBe setOf("calendar-x")
    }
})

private class RecordingEventEnrichmentReadModel(
    private val calendars: List<CalendarEventEnrichments>,
) : EventEnrichmentReadModel {
    val calls = mutableListOf<Pair<UserUuid, Set<String>?>>()

    override suspend fun findCalendars(
        userUuid: UserUuid,
        googleCalendarIds: Set<String>?,
    ): List<CalendarEventEnrichments> {
        calls += userUuid to googleCalendarIds
        return calendars
    }
}
