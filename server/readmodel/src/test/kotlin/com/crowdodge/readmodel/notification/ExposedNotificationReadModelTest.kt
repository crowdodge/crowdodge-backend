package com.crowdodge.readmodel.notification

import com.crowdodge.event.infrastructure.persistence.EventsTable
import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.Location
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import com.crowdodge.user.infrastructure.persistence.UserDevicesTable
import com.crowdodge.user.infrastructure.persistence.UserSettingsTable
import com.crowdodge.user.infrastructure.persistence.UsersTable
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.maps.shouldNotContainKey
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedNotificationReadModelTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("Timed 予定は start_time がそのまま返り isAllDay=false") {
                withReadModel(postgres) { tx, readModel ->
                    val eventUuid = EventUuid(Uuid.random())
                    val start = Instant.parse("2026-07-08T10:00:00Z")
                    tx.inTransaction { insertEvent(eventUuid, title = "打合せ", startTime = start) }

                    val sources = readModel.eventSources(listOf(eventUuid))

                    val source = sources.getValue(eventUuid)
                    source.title shouldBe "打合せ"
                    source.start shouldBe start
                    source.isAllDay shouldBe false
                }
            }

            test("AllDay 予定は当日 0:00 JST の Instant と isAllDay=true") {
                withReadModel(postgres) { tx, readModel ->
                    val eventUuid = EventUuid(Uuid.random())
                    tx.inTransaction { insertEvent(eventUuid, title = null, startDate = LocalDate(2026, 7, 15)) }

                    val sources = readModel.eventSources(listOf(eventUuid))

                    val source = sources.getValue(eventUuid)
                    // 2026-07-15 0:00 JST = 2026-07-14 15:00 UTC
                    source.start shouldBe Instant.parse("2026-07-14T15:00:00Z")
                    source.isAllDay shouldBe true
                    source.title shouldBe null
                }
            }

            test("存在しない eventUuid は Map に含まれない") {
                withReadModel(postgres) { tx, readModel ->
                    val known = EventUuid(Uuid.random())
                    val unknown = EventUuid(Uuid.random())
                    val start = Instant.parse("2026-07-08T10:00:00Z")
                    tx.inTransaction { insertEvent(known, title = "既知", startTime = start) }

                    val sources = readModel.eventSources(listOf(known, unknown))

                    sources shouldContainKey known
                    sources shouldNotContainKey unknown
                }
            }

            test("fcmTokens は userUuid ごとにグルーピングされる") {
                withReadModel(postgres) { tx, readModel ->
                    val userA = UserUuid(Uuid.random())
                    val userB = UserUuid(Uuid.random())
                    tx.inTransaction {
                        insertUser(userA)
                        insertUser(userB)
                        insertDevice(userA, "token-a1")
                        insertDevice(userA, "token-a2")
                        insertDevice(userB, "token-b1")
                    }

                    val tokens = readModel.fcmTokens(listOf(userA, userB))

                    tokens.getValue(userA) shouldContainExactlyInAnyOrder listOf("token-a1", "token-a2")
                    tokens.getValue(userB) shouldContainExactlyInAnyOrder listOf("token-b1")
                }
            }

            test("registrationSources は owner / start / remindTiming / defaultRemindTiming を返す") {
                withReadModel(postgres) { tx, readModel ->
                    val userUuid = UserUuid(Uuid.random())
                    val userCalendarUuid = Uuid.random()
                    val eventUuid = EventUuid(Uuid.random())
                    val start = Instant.parse("2026-07-08T10:00:00Z")
                    tx.inTransaction {
                        insertUser(userUuid)
                        insertUserCalendar(userCalendarUuid, userUuid)
                        insertUserSetting(userUuid, remindTiming = 15.minutes)
                        insertRegistrationEvent(eventUuid, userCalendarUuid, start, remindTiming = 30.minutes)
                    }

                    val source = readModel.registrationSources(listOf(eventUuid)).getValue(eventUuid)

                    source.userUuid shouldBe userUuid
                    source.start shouldBe start
                    source.remindTiming shouldBe 30.minutes
                    source.defaultRemindTiming shouldBe 15.minutes
                }
            }

            test("user_settings 行が無ければ defaultRemindTiming は null（予定は残る）") {
                withReadModel(postgres) { tx, readModel ->
                    val userUuid = UserUuid(Uuid.random())
                    val userCalendarUuid = Uuid.random()
                    val eventUuid = EventUuid(Uuid.random())
                    tx.inTransaction {
                        insertUser(userUuid)
                        insertUserCalendar(userCalendarUuid, userUuid)
                        insertRegistrationEvent(
                            eventUuid,
                            userCalendarUuid,
                            Instant.parse("2026-07-08T10:00:00Z"),
                            remindTiming = 30.minutes,
                        )
                    }

                    val source = readModel.registrationSources(listOf(eventUuid)).getValue(eventUuid)

                    source.remindTiming shouldBe 30.minutes
                    source.defaultRemindTiming shouldBe null
                }
            }

            test("owner（user_calendars 行）が無ければ Map に含まれない") {
                withReadModel(postgres) { tx, readModel ->
                    val eventUuid = EventUuid(Uuid.random())
                    tx.inTransaction {
                        insertRegistrationEvent(
                            eventUuid,
                            userCalendarUuid = Uuid.random(),
                            startTime = Instant.parse("2026-07-08T10:00:00Z"),
                            remindTiming = null,
                        )
                    }

                    readModel.registrationSources(listOf(eventUuid)) shouldNotContainKey eventUuid
                }
            }

            test("events.remind_timing が null でも settings があれば defaultRemindTiming に設定値が入る") {
                withReadModel(postgres) { tx, readModel ->
                    val userUuid = UserUuid(Uuid.random())
                    val userCalendarUuid = Uuid.random()
                    val eventUuid = EventUuid(Uuid.random())
                    tx.inTransaction {
                        insertUser(userUuid)
                        insertUserCalendar(userCalendarUuid, userUuid)
                        insertUserSetting(userUuid, remindTiming = 45.minutes)
                        insertRegistrationEvent(
                            eventUuid,
                            userCalendarUuid,
                            Instant.parse("2026-07-08T10:00:00Z"),
                            remindTiming = null,
                        )
                    }

                    val source = readModel.registrationSources(listOf(eventUuid)).getValue(eventUuid)

                    source.remindTiming shouldBe null
                    source.defaultRemindTiming shouldBe 45.minutes
                }
            }

            test("空リスト入力で空 Map") {
                withReadModel(postgres) { _, readModel ->
                    readModel.eventSources(emptyList()).shouldBeEmpty()
                    readModel.fcmTokens(emptyList()).shouldBeEmpty()
                    readModel.registrationSources(emptyList()).shouldBeEmpty()
                }
            }
        }
    }

    private suspend fun withReadModel(
        postgres: PostgreSQLContainer,
        block: suspend (ExposedTransactionRunner, ExposedNotificationReadModel) -> Unit,
    ) {
        R2dbcFactory.connect(postgres.databaseConfig()).use { conn ->
            suspendTransaction(db = conn.database) {
                SchemaUtils.drop(EventsTable, UserDevicesTable, UserSettingsTable, UserCalendarsTable, UsersTable)
                SchemaUtils.create(UsersTable, UserCalendarsTable, UserSettingsTable, UserDevicesTable, EventsTable)
            }
            val tx = ExposedTransactionRunner(conn.database)
            block(tx, ExposedNotificationReadModel(tx))
        }
    }

    private suspend fun insertEvent(
        eventUuid: EventUuid,
        title: String?,
        startTime: Instant? = null,
        startDate: LocalDate? = null,
    ) {
        EventsTable.insert {
            it[EventsTable.eventUuid] = eventUuid.value
            it[userCalendarUuid] = Uuid.random()
            it[googleEventId] = "google-${eventUuid.value}"
            it[EventsTable.title] = title
            it[EventsTable.startTime] = startTime
            it[EventsTable.startDate] = startDate
        }
    }

    /** registrationSources 用: owner 解決に使う user_calendar_uuid と remind_timing を明示する Timed 予定。 */
    private suspend fun insertRegistrationEvent(
        eventUuid: EventUuid,
        userCalendarUuid: Uuid,
        startTime: Instant,
        remindTiming: Duration?,
    ) {
        EventsTable.insert {
            it[EventsTable.eventUuid] = eventUuid.value
            it[EventsTable.userCalendarUuid] = userCalendarUuid
            it[googleEventId] = "google-${eventUuid.value}"
            it[title] = "打合せ"
            it[EventsTable.startTime] = startTime
            it[EventsTable.remindTiming] = remindTiming
        }
    }

    private suspend fun insertUser(userUuid: UserUuid) {
        UsersTable.insert {
            it[UsersTable.userUuid] = userUuid.value
            it[googleId] = "google-${userUuid.value}"
            it[email] = "${userUuid.value}@example.com"
        }
    }

    private suspend fun insertUserCalendar(userCalendarUuid: Uuid, userUuid: UserUuid) {
        UserCalendarsTable.insert {
            it[UserCalendarsTable.userCalendarUuid] = userCalendarUuid
            it[UserCalendarsTable.userUuid] = userUuid.value
            it[googleCalendarId] = "cal-$userCalendarUuid"
        }
    }

    private suspend fun insertUserSetting(userUuid: UserUuid, remindTiming: Duration) {
        UserSettingsTable.insert {
            it[UserSettingsTable.userUuid] = userUuid.value
            it[home] = Location(longitude = 139.7, latitude = 35.6)
            it[UserSettingsTable.remindTiming] = remindTiming
        }
    }

    private suspend fun insertDevice(userUuid: UserUuid, fcmToken: String) {
        UserDevicesTable.insert {
            it[deviceUuid] = Uuid.random()
            it[UserDevicesTable.userUuid] = userUuid.value
            it[UserDevicesTable.fcmToken] = fcmToken
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
