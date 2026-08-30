package com.crowdodge.readmodel.event

import com.crowdodge.congestion.infrastructure.persistence.EventCongestionForecastsTable
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionsTable
import com.crowdodge.distination.infrastructure.RouteInformation
import com.crowdodge.distination.infrastructure.persistence.EventDestinationLinksTable
import com.crowdodge.distination.infrastructure.persistence.EventDestinationsTable
import com.crowdodge.event.application.port.EventEnrichmentDestination
import com.crowdodge.event.infrastructure.persistence.EventsTable
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.Location
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import com.crowdodge.user.infrastructure.persistence.UsersTable
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedEventEnrichmentReadModelTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("所有カレンダー・予定・目的地・混雑を一括取得し、予定のないカレンダーも返す") {
                withReadModel(postgres) { readModel, db ->
                    val userUuid = UserUuid(Uuid.random())
                    val otherUserUuid = UserUuid(Uuid.random())
                    val calendarUuid = Uuid.random()
                    val emptyCalendarUuid = Uuid.random()
                    val otherCalendarUuid = Uuid.random()
                    val eventUuid = Uuid.random()
                    val noDestinationEventUuid = Uuid.random()

                    suspendTransaction(db = db) {
                        insertUser(userUuid)
                        insertUser(otherUserUuid)
                        insertCalendar(calendarUuid, userUuid, "calendar-a")
                        insertCalendar(emptyCalendarUuid, userUuid, "calendar-empty")
                        insertCalendar(otherCalendarUuid, otherUserUuid, "calendar-other")
                        insertEvent(eventUuid, calendarUuid, "google-event-a")
                        insertDestination(eventUuid)
                        insertCongestions(eventUuid)
                        insertEvent(noDestinationEventUuid, calendarUuid, "google-event-no-destination")
                        insertCongestions(noDestinationEventUuid)
                    }

                    val calendars = readModel.findCalendars(userUuid, null)

                    calendars.map { it.googleCalendarId } shouldContainExactly listOf("calendar-a", "calendar-empty")
                    calendars[1].events shouldBe emptyList()
                    val events = calendars[0].events
                    events.map { it.googleEventId } shouldContainExactly
                        listOf("google-event-a", "google-event-no-destination")
                    events[0].destination shouldBe EventEnrichmentDestination("会場", 35.6, 139.7)
                    events[0].congestions.map { it.start } shouldContainExactly listOf(
                        Instant.parse("2026-07-21T01:00:00Z"),
                        Instant.parse("2026-07-21T03:00:00Z"),
                    )
                    events[1].destination shouldBe null
                    events[1].congestions shouldBe emptyList()
                }
            }

            test("calendarId指定時は所有する指定カレンダーだけを返す") {
                withReadModel(postgres) { readModel, db ->
                    val userUuid = UserUuid(Uuid.random())
                    suspendTransaction(db = db) {
                        insertUser(userUuid)
                        insertCalendar(Uuid.random(), userUuid, "calendar-a")
                        insertCalendar(Uuid.random(), userUuid, "calendar-b")
                    }

                    readModel.findCalendars(userUuid, setOf("calendar-b")).map { it.googleCalendarId } shouldBe
                        listOf("calendar-b")
                    readModel.findCalendars(userUuid, setOf("unknown")) shouldBe emptyList()
                }
            }
        }
    }

    private suspend fun withReadModel(
        postgres: PostgreSQLContainer,
        block: suspend (ExposedEventEnrichmentReadModel, R2dbcDatabase) -> Unit,
    ) {
        R2dbcFactory.connect(postgres.databaseConfig()).use { connection ->
            suspendTransaction(db = connection.database) {
                SchemaUtils.drop(
                    EventCongestionsTable,
                    EventCongestionForecastsTable,
                    EventDestinationLinksTable,
                    EventDestinationsTable,
                    EventsTable,
                    UserCalendarsTable,
                    UsersTable,
                )
                SchemaUtils.create(
                    UsersTable,
                    UserCalendarsTable,
                    EventsTable,
                    EventDestinationsTable,
                    EventDestinationLinksTable,
                    EventCongestionForecastsTable,
                    EventCongestionsTable,
                )
            }
            block(
                ExposedEventEnrichmentReadModel(ExposedTransactionRunner(connection.database)),
                connection.database,
            )
        }
    }

    private suspend fun insertUser(userUuid: UserUuid) {
        UsersTable.insert {
            it[UsersTable.userUuid] = userUuid.value
            it[googleId] = "google-${userUuid.value}"
            it[email] = "${userUuid.value}@example.com"
        }
    }

    private suspend fun insertCalendar(
        calendarUuid: Uuid,
        userUuid: UserUuid,
        googleCalendarId: String,
    ) {
        UserCalendarsTable.insert {
            it[userCalendarUuid] = calendarUuid
            it[UserCalendarsTable.userUuid] = userUuid.value
            it[UserCalendarsTable.googleCalendarId] = googleCalendarId
        }
    }

    private suspend fun insertEvent(eventUuid: Uuid, calendarUuid: Uuid, googleId: String) {
        EventsTable.insert {
            it[EventsTable.eventUuid] = eventUuid
            it[userCalendarUuid] = calendarUuid
            it[googleEventId] = googleId
            it[startTime] = Instant.parse("2026-07-21T00:00:00Z")
            it[endTime] = Instant.parse("2026-07-21T04:00:00Z")
        }
    }

    private suspend fun insertDestination(eventUuid: Uuid) {
        val destinationUuid = Uuid.random()
        EventDestinationsTable.insert {
            it[eventDestinationUuid] = destinationUuid
            it[destination] = "会場"
            it[destinationPoint] = Location(longitude = 139.7, latitude = 35.6)
            it[routeDuration] = 30.minutes
            it[routeInformation] = RouteInformation(emptyList())
        }
        EventDestinationLinksTable.insert {
            it[EventDestinationLinksTable.eventUuid] = eventUuid
            it[eventDestinationUuid] = destinationUuid
            it[createdAt] = Instant.parse("2026-07-21T00:00:00Z")
        }
    }

    private suspend fun insertCongestions(eventUuid: Uuid) {
        val forecastUuid = Uuid.random()
        EventCongestionForecastsTable.insert {
            it[eventCongestionForecastUuid] = forecastUuid
            it[EventCongestionForecastsTable.eventUuid] = eventUuid
            it[generationInputHash] = "a".repeat(64)
            it[generatedAt] = Instant.parse("2026-07-21T00:00:00Z")
        }
        listOf(3, 1).forEach { hour ->
            EventCongestionsTable.insert {
                it[eventCongestionUuid] = Uuid.random()
                it[eventCongestionForecastUuid] = forecastUuid
                it[EventCongestionsTable.eventUuid] = eventUuid
                it[congestionStartTime] = Instant.parse("2026-07-21T0$hour:00:00Z")
                it[congestionEndTime] = Instant.parse("2026-07-21T0${hour + 1}:00:00Z")
                it[area] = "駅前"
                it[description] = "混雑$hour"
            }
        }
    }

    private fun PostgreSQLContainer.databaseConfig() = DatabaseConfig(
        host = host,
        port = firstMappedPort,
        database = databaseName,
        username = username,
        password = password,
    )
}
