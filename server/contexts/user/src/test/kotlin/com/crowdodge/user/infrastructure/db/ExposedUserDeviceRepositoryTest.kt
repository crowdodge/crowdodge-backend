package com.crowdodge.user.infrastructure.db

import arrow.core.raise.either
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.FcmToken.Companion.fcmToken
import com.crowdodge.user.domain.model.UserDevice
import com.crowdodge.user.infrastructure.persistence.UserDevicesTable
import com.crowdodge.user.infrastructure.persistence.UsersTable
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.uuid.Uuid

class ExposedUserDeviceRepositoryTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("同一 fcm_token の再登録は deviceUuid を維持して user_uuid を付け替える") {
                withRepository(postgres) { tx, repo ->
                    val firstUser = insertUser(tx)
                    val secondUser = insertUser(tx)
                    val token = either { fcmToken("token-1") }.getOrNull()!!

                    val original = UserDevice.register(firstUser, token)
                    tx.inTransaction { repo.save(original) }
                    tx.inTransaction { repo.save(UserDevice.register(secondUser, token)) }

                    tx.readOnly {
                        val stored = repo.findByFcmToken(token)!!
                        stored.userDeviceUuid shouldBe original.userDeviceUuid
                        stored.userUuid shouldBe secondUser
                        repo.findByUserUuid(firstUser).size shouldBe 0
                        repo.findByUserUuid(secondUser).size shouldBe 1
                    }
                }
            }
        }
    }

    private suspend fun insertUser(tx: ExposedTransactionRunner): UserUuid {
        val userUuid = UserUuid(Uuid.random())
        tx.inTransaction {
            UsersTable.insert {
                it[UsersTable.userUuid] = userUuid.value
                it[googleId] = "google-${userUuid.value}"
                it[email] = "user-${userUuid.value}@example.com"
            }
        }
        return userUuid
    }

    private suspend fun withRepository(
        postgres: PostgreSQLContainer,
        block: suspend (ExposedTransactionRunner, ExposedUserDeviceRepository) -> Unit,
    ) {
        R2dbcFactory.connect(postgres.databaseConfig()).use { conn ->
            suspendTransaction(db = conn.database) {
                SchemaUtils.drop(UserDevicesTable, UsersTable)
                SchemaUtils.create(UsersTable, UserDevicesTable)
            }
            block(ExposedTransactionRunner(conn.database), ExposedUserDeviceRepository())
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
