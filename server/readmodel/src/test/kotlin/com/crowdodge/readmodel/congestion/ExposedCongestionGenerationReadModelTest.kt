package com.crowdodge.readmodel.congestion

import com.crowdodge.congestion.application.port.CongestionDestination
import com.crowdodge.congestion.application.port.CongestionRoute
import com.crowdodge.congestion.application.port.CongestionRouteStep
import com.crowdodge.congestion.domain.model.EventUuid
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionForecastsTable
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionsTable
import com.crowdodge.distination.infrastructure.RouteInformation
import com.crowdodge.distination.infrastructure.RouteStep
import com.crowdodge.distination.infrastructure.persistence.EventDestinationLinksTable
import com.crowdodge.distination.infrastructure.persistence.EventDestinationsTable
import com.crowdodge.event.infrastructure.persistence.EventsTable
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.Location
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import kotlinx.datetime.LocalDate
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
import kotlinx.datetime.Instant as DateTimeInstant

class ExposedCongestionGenerationReadModelTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("予定・目的地・往路・保存forecastを一括でcandidateへ射影する") {
                withReadModel(postgres) { tx, readModel, db ->
                    val eventUuid = insertTimedEvent(db, withDestination = true)
                    insertForecast(db, eventUuid)

                    val result = readModel.findAll(setOf(EventUuid(eventUuid)))
                    val candidate = result[EventUuid(eventUuid)]!!

                    candidate.source.start shouldBe Instant.parse("2026-08-01T01:00:00Z")
                    candidate.source.end shouldBe Instant.parse("2026-08-01T03:00:00Z")
                    candidate.source.isAllDay shouldBe false
                    candidate.source.destination shouldBe CongestionDestination(
                        name = "会場",
                        latitude = 35.6298,
                        longitude = 139.7942,
                    )
                    candidate.source.outboundRoute shouldBe CongestionRoute(
                        steps = listOf(
                            CongestionRouteStep(
                                fromName = "出発",
                                toName = "駅",
                                lineName = "路線",
                                moveType = "local_train",
                                callingAt = listOf("途中駅A", "途中駅B"),
                            ),
                        ),
                    )
                    candidate.source.travelDuration shouldBe 30.minutes
                    candidate.savedForecast!!.generationInputHash shouldBe "hash"
                    candidate.savedForecast!!.periods.single().description shouldBe "混雑"
                    tx.readOnly { result.size shouldBe 1 }
                }
            }

            test("終日予定はbusiness dateの開始時刻へ変換し、目的地のない予定は除外する") {
                withReadModel(postgres) { _, readModel, db ->
                    val allDay = insertAllDayEvent(db, withDestination = true)
                    val withoutDestination = insertTimedEvent(db, withDestination = false)

                    val result = readModel.findAll(
                        setOf(EventUuid(allDay), EventUuid(withoutDestination), EventUuid(Uuid.random())),
                    )

                    result.keys shouldBe setOf(EventUuid(allDay))
                    result[EventUuid(allDay)]!!.source.start shouldBe
                        Instant.parse("2026-08-01T15:00:00Z")
                }
            }
        }

        test("空入力ではreadOnly transactionを開始しない") {
            val transactions = RecordingTransactionRunner()
            val readModel = ExposedCongestionGenerationReadModel(transactions)

            readModel.findAll(emptySet()) shouldBe emptyMap()
            transactions.readOnlyCalls shouldBe 0
        }
    }

    private suspend fun withReadModel(
        postgres: PostgreSQLContainer,
        block: suspend (ExposedTransactionRunner, ExposedCongestionGenerationReadModel, R2dbcDatabase) -> Unit,
    ) {
        R2dbcFactory.connect(
            DatabaseConfig(
                host = postgres.host,
                port = postgres.firstMappedPort,
                database = postgres.databaseName,
                username = postgres.username,
                password = postgres.password,
            ),
        ).use { connection ->
            suspendTransaction(db = connection.database) {
                SchemaUtils.drop(
                    EventCongestionsTable,
                    EventCongestionForecastsTable,
                    EventDestinationLinksTable,
                    EventDestinationsTable,
                    EventsTable,
                )
                SchemaUtils.create(
                    EventsTable,
                    EventDestinationsTable,
                    EventDestinationLinksTable,
                    EventCongestionForecastsTable,
                    EventCongestionsTable,
                )
            }
            block(
                ExposedTransactionRunner(connection.database),
                ExposedCongestionGenerationReadModel(ExposedTransactionRunner(connection.database)),
                connection.database,
            )
        }
    }

    private suspend fun insertTimedEvent(
        db: R2dbcDatabase,
        withDestination: Boolean,
    ): Uuid = suspendTransaction(db = db) {
        val eventUuid = Uuid.random()
        EventsTable.insert {
            it[EventsTable.eventUuid] = eventUuid
            it[userCalendarUuid] = Uuid.random()
            it[googleEventId] = eventUuid.toString()
            it[startTime] = Instant.parse("2026-08-01T01:00:00Z")
            it[endTime] = Instant.parse("2026-08-01T03:00:00Z")
        }
        if (withDestination) insertDestination(eventUuid)
        eventUuid
    }

    private suspend fun insertAllDayEvent(
        db: R2dbcDatabase,
        withDestination: Boolean,
    ): Uuid = suspendTransaction(db = db) {
        val eventUuid = Uuid.random()
        EventsTable.insert {
            it[EventsTable.eventUuid] = eventUuid
            it[userCalendarUuid] = Uuid.random()
            it[googleEventId] = eventUuid.toString()
            it[startDate] = LocalDate(2026, 8, 2)
            it[endDate] = LocalDate(2026, 8, 3)
        }
        if (withDestination) insertDestination(eventUuid)
        eventUuid
    }

    private suspend fun insertDestination(eventUuid: Uuid) {
        val destinationUuid = Uuid.random()
        EventDestinationsTable.insert {
            it[eventDestinationUuid] = destinationUuid
            it[destination] = "会場"
            it[destinationPoint] = Location(longitude = 139.7942, latitude = 35.6298)
            it[routeDuration] = 30.minutes
            it[routeInformation] = ROUTE
        }
        EventDestinationLinksTable.insert {
            it[EventDestinationLinksTable.eventUuid] = eventUuid
            it[eventDestinationUuid] = destinationUuid
            it[createdAt] = DateTimeInstant.parse("2026-08-01T00:00:00Z")
        }
    }

    private suspend fun insertForecast(db: R2dbcDatabase, eventUuid: Uuid) = suspendTransaction(db = db) {
        val forecastUuid = Uuid.random()
        EventCongestionForecastsTable.insert {
            it[eventCongestionForecastUuid] = forecastUuid
            it[EventCongestionForecastsTable.eventUuid] = eventUuid
            it[generationInputHash] = "hash"
            it[generatedAt] = Instant.parse("2026-08-01T00:00:00Z")
        }
        EventCongestionsTable.insert {
            it[eventCongestionUuid] = Uuid.random()
            it[EventCongestionsTable.eventCongestionForecastUuid] = forecastUuid
            it[EventCongestionsTable.eventUuid] = eventUuid
            it[congestionStartTime] = Instant.parse("2026-08-01T02:00:00Z")
            it[congestionEndTime] = Instant.parse("2026-08-01T03:00:00Z")
            it[area] = "area"
            it[description] = "混雑"
        }
    }

    private class RecordingTransactionRunner : com.crowdodge.shared.kernel.TransactionRunner {
        var readOnlyCalls = 0

        override suspend fun <T> inTransaction(block: suspend () -> T): T = block()

        override suspend fun <T> readOnly(block: suspend () -> T): T {
            readOnlyCalls += 1
            return block()
        }
    }

    private companion object {
        val ROUTE = RouteInformation(
            routeSteps = listOf(
                RouteStep(
                    fromName = "出発",
                    toName = "駅",
                    lineName = "路線",
                    moveType = "local_train",
                    durationMin = 20,
                    distanceMeter = 1000,
                    callingAt = listOf("途中駅A", "途中駅B"),
                ),
            ),
        )
    }
}
