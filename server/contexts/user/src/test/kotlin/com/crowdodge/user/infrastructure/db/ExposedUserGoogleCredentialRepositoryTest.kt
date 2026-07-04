package com.crowdodge.user.infrastructure.db

import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.TokenCipher
import com.crowdodge.user.infrastructure.persistence.UserGoogleCredentialsTable
import com.crowdodge.user.infrastructure.persistence.UsersTable
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.CancellationException
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedUserGoogleCredentialRepositoryTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("復号中の CancellationException は再スローする") {
                R2dbcFactory.connect(postgres.databaseConfig()).use { connection ->
                    val userUuid = UserUuid(Uuid.parse("30000000-0000-0000-0000-000000000001"))
                    val cancellation = CancellationException("decrypt cancelled")
                    val repository = ExposedUserGoogleCredentialRepository(ThrowingTokenCipher(cancellation))

                    val exception = suspendTransaction(db = connection.database) {
                        createSchema()
                        insertUser(userUuid, "cancelled@example.com", "cancelled-google")
                        insertCredential(userUuid)

                        shouldThrow<CancellationException> {
                            repository.findByUserUuid(userUuid)
                        }
                    }

                    exception shouldBeSameInstanceAs cancellation
                }
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

    private suspend fun createSchema() {
        SchemaUtils.drop(UserGoogleCredentialsTable, UsersTable)
        SchemaUtils.create(UsersTable, UserGoogleCredentialsTable)
    }

    private suspend fun insertUser(
        userUuid: UserUuid,
        email: String,
        googleId: String,
    ) {
        UsersTable.insert {
            it[UsersTable.userUuid] = userUuid.value
            it[UsersTable.email] = email
            it[UsersTable.googleId] = googleId
        }
    }

    private suspend fun insertCredential(userUuid: UserUuid) {
        UserGoogleCredentialsTable.insert {
            it[UserGoogleCredentialsTable.userUuid] = userUuid.value
            it[UserGoogleCredentialsTable.googleSubject] = "google-subject-${userUuid.value}"
            it[UserGoogleCredentialsTable.accessToken] = "encrypted-access-token"
            it[UserGoogleCredentialsTable.refreshToken] = "encrypted-refresh-token"
            it[UserGoogleCredentialsTable.accessTokenExpiresAt] = Instant.parse("2026-06-28T01:00:00Z")
            it[UserGoogleCredentialsTable.grantedScopes] = "https://www.googleapis.com/auth/calendar.events"
        }
    }
}

private class ThrowingTokenCipher(
    private val exception: Throwable,
) : TokenCipher {
    override fun encrypt(plainText: String): String = plainText

    override fun decrypt(encodedCipherText: String): String {
        throw exception
    }
}
