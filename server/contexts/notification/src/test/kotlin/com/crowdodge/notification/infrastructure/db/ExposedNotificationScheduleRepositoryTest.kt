package com.crowdodge.notification.infrastructure.db

import com.crowdodge.notification.domain.model.EventUuid
import com.crowdodge.notification.domain.model.NotificationKind
import com.crowdodge.notification.domain.model.NotificationSchedule
import com.crowdodge.notification.infrastructure.persistence.NotificationSchedulesTable
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.UserUuid
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.statements.SuspendStatementInterceptor
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedNotificationScheduleRepositoryTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            val now = Instant.parse("2026-07-08T00:00:00Z")

            fun pending(
                eventUuid: EventUuid = EventUuid(Uuid.random()),
                notificateTime: Instant = now,
                kind: NotificationKind = NotificationKind.Reminder,
            ) = NotificationSchedule.schedule(UserUuid(Uuid.random()), eventUuid, kind, notificateTime)

            test("保存した集約を eventUuid の pending として取得できる") {
                withRepository(postgres) { _, tx, repo ->
                    val eventUuid = EventUuid(Uuid.random())
                    val a = pending(eventUuid)
                    val b = pending(eventUuid, kind = NotificationKind.CongestionAlert)
                    val other = pending()

                    tx.inTransaction { repo.saveAll(listOf(a, b, other)) }

                    tx.readOnly {
                        repo.findPendingByEventUuid(eventUuid) shouldContainExactlyInAnyOrder listOf(a, b)
                    }
                }
            }

            test("saveAll は複数スケジュールを単一の SQL 実行で保存する") {
                withRepository(postgres) { database, _, repo ->
                    val statements = StatementCounter()
                    suspendTransaction(db = database) {
                        TransactionManager.current().registerInterceptor(statements)
                        repo.saveAll(listOf(pending(), pending(), pending()))
                    }

                    statements.executed shouldBe 1
                }
            }

            test("save は状態遷移後の上書き保存になる") {
                withRepository(postgres) { _, tx, repo ->
                    val schedule = pending()
                    tx.inTransaction { repo.save(schedule) }
                    val processing = schedule.markProcessing().shouldBeRight()
                    tx.inTransaction { repo.save(processing) }

                    tx.readOnly {
                        repo.findPendingByEventUuid(schedule.eventUuid).shouldBeEmpty()
                        repo.findDue(now).shouldBeEmpty()
                    }
                }
            }

            test("findDue は notificate_time 到来の pending のみ返す") {
                withRepository(postgres) { _, tx, repo ->
                    val due = pending(notificateTime = now)
                    val future = pending(notificateTime = Instant.parse("2026-07-09T00:00:00Z"))
                    val canceled = pending(notificateTime = now).cancel().shouldBeRight()
                    tx.inTransaction {
                        repo.saveAll(listOf(due, future))
                        repo.save(canceled)
                    }

                    tx.readOnly {
                        repo.findDue(now) shouldContainExactlyInAnyOrder listOf(due)
                    }
                }
            }

            test("deletePendingByEventUuid は pending だけを消す") {
                withRepository(postgres) { _, tx, repo ->
                    val eventUuid = EventUuid(Uuid.random())
                    val p = pending(eventUuid)
                    val done = pending(eventUuid).markProcessing().shouldBeRight().complete().shouldBeRight()
                    tx.inTransaction {
                        repo.save(p)
                        repo.save(done)
                    }

                    tx.inTransaction { repo.deletePendingByEventUuid(eventUuid) }

                    tx.readOnly {
                        repo.findPendingByEventUuid(eventUuid).shouldBeEmpty()
                    }
                    // completed は残る（findDue/findPending には現れないが行として存在）
                    tx.readOnly {
                        repo.findDue(now)
                            .none { it.notificationScheduleUuid == done.notificationScheduleUuid } shouldBe true
                    }
                }
            }
        }
    }

    private suspend fun withRepository(
        postgres: PostgreSQLContainer,
        block: suspend (R2dbcDatabase, ExposedTransactionRunner, ExposedNotificationScheduleRepository) -> Unit,
    ) {
        R2dbcFactory.connect(postgres.databaseConfig()).use { conn ->
            suspendTransaction(db = conn.database) {
                SchemaUtils.drop(NotificationSchedulesTable)
                SchemaUtils.create(NotificationSchedulesTable)
            }
            block(conn.database, ExposedTransactionRunner(conn.database), ExposedNotificationScheduleRepository())
        }
    }

    private fun PostgreSQLContainer.databaseConfig() = DatabaseConfig(
        host = host,
        port = firstMappedPort,
        database = databaseName,
        username = username,
        password = password,
    )

    private class StatementCounter : SuspendStatementInterceptor {
        var executed = 0

        override suspend fun afterExecution(
            transaction: org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction,
            contexts: List<org.jetbrains.exposed.v1.core.statements.StatementContext>,
            executedStatement: org.jetbrains.exposed.v1.r2dbc.statements.api.R2dbcPreparedStatementApi,
        ) {
            executed += 1
        }
    }
}
