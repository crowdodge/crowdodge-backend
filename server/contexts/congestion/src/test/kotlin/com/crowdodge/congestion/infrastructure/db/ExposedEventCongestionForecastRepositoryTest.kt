package com.crowdodge.congestion.infrastructure.db

import arrow.core.raise.either
import com.crowdodge.congestion.domain.model.CongestionPeriod
import com.crowdodge.congestion.domain.model.CongestionPeriod.Companion.congestionPeriod
import com.crowdodge.congestion.domain.model.EventCongestionForecast
import com.crowdodge.congestion.domain.model.EventCongestionForecast.Companion.forecast
import com.crowdodge.congestion.domain.model.EventUuid
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionForecastsTable
import com.crowdodge.congestion.infrastructure.persistence.EventCongestionsTable
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcFactory
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedEventCongestionForecastRepositoryTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("同じ予定のforecastと子行を置換し、空配列では親だけを残す") {
                withRepository(postgres) { tx, repository ->
                    val eventUuid = EventUuid(Uuid.random())
                    val old = period("old")
                    val replacement = listOf(period("new-1"), period("new-2"))

                    tx.inTransaction { repository.replace(forecast(eventUuid, "a", listOf(old))) }
                    tx.inTransaction { repository.replace(forecast(eventUuid, "b", replacement)) }

                    tx.readOnly {
                        val rows = EventCongestionsTable.selectAll().toList()
                        rows.map { it[EventCongestionsTable.description] } shouldContainExactly listOf("new-1", "new-2")
                        rows.all { it[EventCongestionsTable.eventUuid] == eventUuid.value } shouldBe true
                        rows.all {
                            it[EventCongestionsTable.eventCongestionForecastUuid] ==
                                rows.first()[EventCongestionsTable.eventCongestionForecastUuid]
                        } shouldBe true
                    }

                    tx.inTransaction { repository.replace(forecast(eventUuid, "c", emptyList())) }
                    tx.readOnly {
                        EventCongestionsTable.selectAll().toList().size shouldBe 0
                        EventCongestionForecastsTable.selectAll().toList().size shouldBe 1
                    }
                }
            }
        }
    }

    private suspend fun withRepository(
        postgres: PostgreSQLContainer,
        block: suspend (ExposedTransactionRunner, ExposedEventCongestionForecastRepository) -> Unit,
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
                SchemaUtils.drop(EventCongestionsTable, EventCongestionForecastsTable)
                SchemaUtils.create(EventCongestionForecastsTable, EventCongestionsTable)
            }
            block(ExposedTransactionRunner(connection.database), ExposedEventCongestionForecastRepository())
        }
    }

    private fun period(description: String): CongestionPeriod = either {
        congestionPeriod(
            start = Instant.parse("2026-08-01T01:00:00Z"),
            end = Instant.parse("2026-08-01T02:00:00Z"),
            area = "area",
            description = description,
        )
    }.getOrNull()!!

    private fun forecast(
        eventUuid: EventUuid,
        hashSeed: String,
        periods: List<CongestionPeriod>,
    ): EventCongestionForecast = either {
        forecast(eventUuid, hashSeed.repeat(64), SAVED_AT, periods)
    }.getOrNull()!!

    private companion object {
        val SAVED_AT: Instant = Instant.parse("2026-08-01T00:00:00Z")
    }
}
