package com.crowdodge.shared.infra.messaging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventHandler
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Clock
import kotlin.time.Instant

class TransactionalInProcessDomainEventPublisherTest : FunSpec() {
    private object Records : Table("domain_event_publisher_records") {
        val id = integer("id")
        val name = varchar("name", length = 50)

        override val primaryKey = PrimaryKey(id)
    }

    init {
        test("transaction外のpublishはIllegalStateExceptionになる") {
            val publisher = TransactionalInProcessDomainEventPublisher(
                handlers = listOf(RecordingHandler()),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            shouldThrow<IllegalStateException> {
                publisher.publish(TestEvent())
            }
        }

        test("handlerProviderはpublisher構築時には呼び出されない") {
            var requestedCount = 0

            TransactionalInProcessDomainEventPublisher(
                handlerProvider = {
                    requestedCount += 1
                    emptyList<DomainEventHandler>()
                },
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            )

            requestedCount shouldBe 0
        }

        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("commit後に1回配送する") {
                R2dbcFactory.connect(
                    DatabaseConfig(
                        host = postgres.host,
                        port = postgres.firstMappedPort,
                        database = postgres.databaseName,
                        username = postgres.username,
                        password = postgres.password,
                    ),
                ).use { conn ->
                    prepareSchema(conn.database)
                    val firstDelivery = CompletableDeferred<Int>()
                    val secondDelivery = CompletableDeferred<Int>()
                    val handler = RecordingHandler(
                        onHandled = { count ->
                            if (count == 1) firstDelivery.complete(count)
                            if (count == 2) secondDelivery.complete(count)
                        },
                    )
                    val unsupportedHandler = RecordingHandler(supported = false)
                    val publisher = TransactionalInProcessDomainEventPublisher(
                        handlers = listOf(handler, unsupportedHandler),
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    )

                    ExposedTransactionRunner(conn.database).inTransaction {
                        Records.insert {
                            it[id] = 1
                            it[name] = "committed"
                        }
                        publisher.publish(TestEvent())
                    }

                    withTimeout(2_000) { firstDelivery.await() } shouldBe 1
                    withTimeoutOrNull(200) { secondDelivery.await() } shouldBe null
                    unsupportedHandler.handledCount shouldBe 0
                }
            }

            test("rollback時は配送しない") {
                R2dbcFactory.connect(
                    DatabaseConfig(
                        host = postgres.host,
                        port = postgres.firstMappedPort,
                        database = postgres.databaseName,
                        username = postgres.username,
                        password = postgres.password,
                    ),
                ).use { conn ->
                    prepareSchema(conn.database)
                    val delivered = CompletableDeferred<Int>()
                    val handler = RecordingHandler(onHandled = { delivered.complete(it) })
                    val publisher = TransactionalInProcessDomainEventPublisher(
                        handlers = listOf(handler),
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    )

                    shouldThrow<RollbackSignal> {
                        ExposedTransactionRunner(conn.database).inTransaction {
                            Records.insert {
                                it[id] = 2
                                it[name] = "rolled-back"
                            }
                            publisher.publish(TestEvent())
                            throw RollbackSignal()
                        }
                    }

                    withTimeoutOrNull(200) { delivered.await() } shouldBe null
                    handler.handledCount shouldBe 0
                }
            }

            test("commit後のHandler失敗はcommit済みDB更新を戻さない") {
                R2dbcFactory.connect(
                    DatabaseConfig(
                        host = postgres.host,
                        port = postgres.firstMappedPort,
                        database = postgres.databaseName,
                        username = postgres.username,
                        password = postgres.password,
                    ),
                ).use { conn ->
                    prepareSchema(conn.database)
                    val attempted = CompletableDeferred<Unit>()
                    val handler = RecordingHandler(
                        onHandled = {
                            attempted.complete(Unit)
                            error("handler failed")
                        },
                    )
                    val publisher = TransactionalInProcessDomainEventPublisher(
                        handlers = listOf(handler),
                        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    )

                    withPublisherLogs { logs ->
                        ExposedTransactionRunner(conn.database).inTransaction {
                            Records.insert {
                                it[id] = 3
                                it[name] = "handler-failed"
                            }
                            publisher.publish(TestEvent())
                        }
                        withTimeout(2_000) { attempted.await() }
                        logs.awaitHandlerFailureLog()
                    }

                    suspendTransaction(db = conn.database) {
                        Records.selectAll().where { Records.id eq 3 }.firstOrNull()?.get(Records.name)
                    } shouldBe "handler-failed"
                }
            }
        }
    }

    private suspend fun prepareSchema(db: org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase) {
        suspendTransaction(db = db) {
            SchemaUtils.drop(Records)
            SchemaUtils.create(Records)
        }
    }

    private data class TestEvent(
        override val occurredAt: Instant = Clock.System.now(),
    ) : DomainEvent

    private class RollbackSignal : RuntimeException()

    private class RecordingHandler(
        private val onHandled: suspend (Int) -> Unit = {},
        private val supported: Boolean = true,
    ) : DomainEventHandler {
        var handledCount: Int = 0
            private set

        override fun supports(event: DomainEvent): Boolean = supported && event is TestEvent

        override suspend fun handle(event: DomainEvent) {
            handledCount += 1
            onHandled(handledCount)
        }
    }

    private suspend fun <T> withPublisherLogs(block: suspend (ListAppender<ILoggingEvent>) -> T): T {
        val logger = LoggerFactory.getLogger(TransactionalInProcessDomainEventPublisher::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        return try {
            block(appender)
        } finally {
            logger.detachAppender(appender)
            appender.stop()
        }
    }

    private suspend fun ListAppender<ILoggingEvent>.awaitHandlerFailureLog() {
        withTimeout(2_000) {
            while (
                list.none { event ->
                    event.level == Level.ERROR && event.formattedMessage == "Domain Event handler failed"
                }
            ) {
                delay(10)
            }
        }
    }
}
