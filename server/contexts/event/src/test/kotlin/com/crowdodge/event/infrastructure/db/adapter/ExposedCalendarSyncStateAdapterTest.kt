package com.crowdodge.event.infrastructure.db.adapter

import com.crowdodge.event.application.port.CalendarSyncState
import com.crowdodge.event.application.port.CalendarWatchRegistration
import com.crowdodge.event.domain.model.UserCalendarUuid
import com.crowdodge.event.infrastructure.persistence.EventCalendarSyncsTable
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcFactory
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedCalendarSyncStateAdapterTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("保存した同期状態をuserCalendarUuidとchannelIdで取得できる") {
                withAdapter(postgres) { transactionRunner, adapter ->
                    val state = state(
                        userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000001")),
                        syncToken = "sync-token-1",
                        materializedUntil = Instant.parse("2026-07-01T00:00:00Z"),
                        watchChannelId = "channel-1",
                        watchResourceId = "resource-1",
                        watchChannelToken = "token-1",
                        watchExpiration = Instant.parse("2026-07-08T00:00:00Z"),
                    )

                    transactionRunner.inTransaction {
                        adapter.saveProvisioned(state)
                    }

                    transactionRunner.readOnly {
                        adapter.find(state.userCalendarUuid) shouldBe state
                        adapter.findByChannelId("channel-1") shouldBe state
                    }
                }
            }

            test("lockは同期状態をFOR UPDATE取得する") {
                withAdapter(postgres) { transactionRunner, adapter ->
                    val state = state(
                        userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000002")),
                        watchChannelId = "channel-2",
                    )
                    transactionRunner.inTransaction { adapter.saveProvisioned(state) }

                    transactionRunner.inTransaction {
                        adapter.lock(state.userCalendarUuid) shouldBe state
                    }
                }
            }

            test("updateAfterSyncはsyncTokenとmaterializedUntilだけを更新する") {
                withAdapter(postgres) { transactionRunner, adapter ->
                    val userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000003"))
                    val before = state(
                        userCalendarUuid = userCalendarUuid,
                        syncToken = "old-sync-token",
                        materializedUntil = Instant.parse("2026-07-01T00:00:00Z"),
                        watchChannelId = "channel-3",
                        watchResourceId = "resource-3",
                        watchChannelToken = "token-3",
                        watchExpiration = Instant.parse("2026-07-08T00:00:00Z"),
                    )
                    transactionRunner.inTransaction {
                        adapter.saveProvisioned(before)
                        adapter.updateAfterSync(
                            userCalendarUuid = userCalendarUuid,
                            nextSyncToken = "new-sync-token",
                            materializedUntil = Instant.parse("2026-07-02T00:00:00Z"),
                        )
                    }

                    transactionRunner.readOnly {
                        adapter.find(userCalendarUuid) shouldBe before.copy(
                            syncToken = "new-sync-token",
                            materializedUntil = Instant.parse("2026-07-02T00:00:00Z"),
                        )
                    }
                }
            }

            test("replaceWatchはchannelIdが一致する場合だけwatchを交換する") {
                withAdapter(postgres) { transactionRunner, adapter ->
                    val userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000004"))
                    val before = state(
                        userCalendarUuid = userCalendarUuid,
                        watchChannelId = "old-channel",
                        watchResourceId = "old-resource",
                        watchChannelToken = "old-token",
                        watchExpiration = Instant.parse("2026-07-08T00:00:00Z"),
                    )
                    val replacement = CalendarWatchRegistration(
                        channelId = "new-channel",
                        resourceId = "new-resource",
                        channelToken = "new-token",
                        expiration = Instant.parse("2026-07-09T00:00:00Z"),
                    )
                    transactionRunner.inTransaction { adapter.saveProvisioned(before) }

                    transactionRunner.inTransaction {
                        adapter.replaceWatch(userCalendarUuid, expectedChannelId = "other-channel", watch = replacement)
                    } shouldBe false
                    transactionRunner.readOnly {
                        adapter.find(userCalendarUuid) shouldBe before
                    }

                    transactionRunner.inTransaction {
                        adapter.replaceWatch(userCalendarUuid, expectedChannelId = "old-channel", watch = replacement)
                    } shouldBe true
                    transactionRunner.readOnly {
                        adapter.find(userCalendarUuid) shouldBe before.copy(
                            watchChannelId = "new-channel",
                            watchResourceId = "new-resource",
                            watchChannelToken = "new-token",
                            watchExpiration = Instant.parse("2026-07-09T00:00:00Z"),
                        )
                    }
                }
            }

            test("deleteIfChannelMatchesはchannelIdが一致する場合だけ削除する") {
                withAdapter(postgres) { transactionRunner, adapter ->
                    val state = state(
                        userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000005")),
                        watchChannelId = "delete-channel",
                    )
                    transactionRunner.inTransaction { adapter.saveProvisioned(state) }

                    transactionRunner.inTransaction {
                        adapter.deleteIfChannelMatches(state.userCalendarUuid, channelId = "other-channel")
                    } shouldBe false
                    transactionRunner.readOnly {
                        adapter.find(state.userCalendarUuid) shouldBe state
                    }

                    transactionRunner.inTransaction {
                        adapter.deleteIfChannelMatches(state.userCalendarUuid, channelId = "delete-channel")
                    } shouldBe true
                    transactionRunner.readOnly {
                        adapter.find(state.userCalendarUuid) shouldBe null
                    }
                }
            }

            test("deleteはuserCalendarUuidで同期状態を削除する") {
                withAdapter(postgres) { transactionRunner, adapter ->
                    val target = state(
                        userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000008")),
                        watchChannelId = "delete-by-uuid",
                    )
                    val other = state(
                        userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000009")),
                        watchChannelId = "keep-by-uuid",
                    )
                    transactionRunner.inTransaction {
                        adapter.saveProvisioned(target)
                        adapter.saveProvisioned(other)
                        adapter.delete(target.userCalendarUuid) shouldBe true
                    }

                    transactionRunner.readOnly {
                        adapter.find(target.userCalendarUuid) shouldBe null
                        adapter.find(other.userCalendarUuid) shouldBe other
                    }
                }
            }

            test("listAllは全同期状態を返す") {
                withAdapter(postgres) { transactionRunner, adapter ->
                    val first = state(
                        userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000006")),
                        watchChannelId = "list-channel-1",
                    )
                    val second = state(
                        userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000007")),
                        watchChannelId = "list-channel-2",
                    )
                    transactionRunner.inTransaction {
                        adapter.saveProvisioned(first)
                        adapter.saveProvisioned(second)
                    }

                    transactionRunner.readOnly {
                        adapter.listAll() shouldContainExactlyInAnyOrder listOf(first, second)
                    }
                }
            }
        }
    }

    private suspend fun withAdapter(
        postgres: PostgreSQLContainer,
        block: suspend (ExposedTransactionRunner, ExposedCalendarSyncStateAdapter) -> Unit,
    ) {
        R2dbcFactory.connect(postgres.databaseConfig()).use { conn ->
            suspendTransaction(db = conn.database) {
                SchemaUtils.drop(EventCalendarSyncsTable)
                SchemaUtils.create(EventCalendarSyncsTable)
            }
            block(
                ExposedTransactionRunner(conn.database),
                ExposedCalendarSyncStateAdapter(),
            )
        }
    }

    @Suppress("LongParameterList")
    private fun state(
        userCalendarUuid: UserCalendarUuid,
        syncToken: String? = null,
        materializedUntil: Instant? = null,
        watchChannelId: String? = null,
        watchResourceId: String? = null,
        watchChannelToken: String? = null,
        watchExpiration: Instant? = null,
    ): CalendarSyncState = CalendarSyncState(
        userCalendarUuid = userCalendarUuid,
        syncToken = syncToken,
        materializedUntil = materializedUntil,
        watchChannelId = watchChannelId,
        watchResourceId = watchResourceId,
        watchChannelToken = watchChannelToken,
        watchExpiration = watchExpiration,
    )

    private fun PostgreSQLContainer.databaseConfig() = DatabaseConfig(
        host = host,
        port = firstMappedPort,
        database = databaseName,
        username = username,
        password = password,
    )
}
