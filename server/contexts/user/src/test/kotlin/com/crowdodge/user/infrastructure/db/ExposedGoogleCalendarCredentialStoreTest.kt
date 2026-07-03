package com.crowdodge.user.infrastructure.db

import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.TokenCipher
import com.crowdodge.user.domain.model.UserCalendarUuid
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import com.crowdodge.user.infrastructure.persistence.UserGoogleCredentialsTable
import com.crowdodge.user.infrastructure.persistence.UsersTable
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ExposedGoogleCalendarCredentialStoreTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("calendar owner の user credential を復号して返し、別ユーザーの credential を返さない") {
                R2dbcFactory.connect(postgres.databaseConfig()).use { conn ->
                    val store = ExposedGoogleCalendarCredentialStore(PrefixTokenCipher)
                    val ownerCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000001"))
                    val otherCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000002"))
                    val ownerUserUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000001"))
                    val otherUserUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000002"))
                    val expiresAt = Instant.parse("2026-06-27T01:00:00Z")

                    val ownerCredential = suspendTransaction(db = conn.database) {
                        createSchema()
                        insertUser(ownerUserUuid, "owner@example.com", "owner-google")
                        insertUser(otherUserUuid, "other@example.com", "other-google")
                        insertCalendar(ownerCalendarUuid, ownerUserUuid, "owner-calendar@example.com")
                        insertCalendar(otherCalendarUuid, otherUserUuid, "other-calendar@example.com")
                        insertCredential(ownerUserUuid, "owner-sub", "owner-access", "owner-refresh", expiresAt)
                        insertCredential(otherUserUuid, "other-sub", "other-access", "other-refresh", expiresAt)

                        store.find(ownerCalendarUuid)
                    }

                    ownerCredential?.userUuid shouldBe ownerUserUuid
                    ownerCredential?.googleCalendarId shouldBe "owner-calendar@example.com"
                    ownerCredential?.accessToken shouldBe "owner-access"
                    ownerCredential?.refreshToken shouldBe "owner-refresh"
                    ownerCredential?.expiresAt shouldBe expiresAt

                    val otherCredential = suspendTransaction(db = conn.database) {
                        store.find(otherCalendarUuid)
                    }
                    otherCredential?.userUuid shouldBe otherUserUuid
                    otherCredential?.googleCalendarId shouldBe "other-calendar@example.com"
                    otherCredential?.accessToken shouldBe "other-access"
                }
            }

            test("refresh 後の access token は userUuid をキーに暗号化して保存する") {
                R2dbcFactory.connect(postgres.databaseConfig()).use { conn ->
                    val store = ExposedGoogleCalendarCredentialStore(PrefixTokenCipher)
                    val userUuid = UserUuid(Uuid.parse("20000000-0000-0000-0000-000000000001"))
                    val userCalendarUuid = UserCalendarUuid(Uuid.parse("00000000-0000-0000-0000-000000000003"))
                    val initialExpiresAt = Instant.parse("2026-06-27T01:00:00Z")
                    val refreshedExpiresAt = Instant.parse("2026-06-27T02:00:00Z")

                    val storedAccessToken = suspendTransaction(db = conn.database) {
                        createSchema()
                        insertUser(userUuid, "refresh@example.com", "refresh-google")
                        insertCalendar(userCalendarUuid, userUuid, "refresh-calendar@example.com")
                        insertCredential(userUuid, "refresh-sub", "old-access", "refresh-token", initialExpiresAt)

                        store.updateAccessToken(userUuid, "refreshed-access", refreshedExpiresAt)

                        UserGoogleCredentialsTable.selectAll()
                            .where { UserGoogleCredentialsTable.userUuid eq userUuid.value }
                            .firstOrNull()
                            ?.let {
                                it[UserGoogleCredentialsTable.accessToken] to
                                    it[UserGoogleCredentialsTable.accessTokenExpiresAt]
                            }
                    }

                    storedAccessToken?.first shouldBe "encrypted:refreshed-access"
                    storedAccessToken?.second shouldBe refreshedExpiresAt
                    PrefixTokenCipher.decrypt(storedAccessToken?.first ?: "") shouldBe "refreshed-access"
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
        SchemaUtils.drop(UserGoogleCredentialsTable, UserCalendarsTable, UsersTable)
        SchemaUtils.create(UsersTable, UserCalendarsTable, UserGoogleCredentialsTable)
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

    private suspend fun insertCalendar(
        userCalendarUuid: UserCalendarUuid,
        userUuid: UserUuid,
        googleCalendarId: String,
    ) {
        UserCalendarsTable.insert {
            it[UserCalendarsTable.userCalendarUuid] = userCalendarUuid.value
            it[UserCalendarsTable.userUuid] = userUuid.value
            it[UserCalendarsTable.googleCalendarId] = googleCalendarId
        }
    }

    private suspend fun insertCredential(
        userUuid: UserUuid,
        googleSubject: String,
        accessToken: String,
        refreshToken: String,
        expiresAt: Instant,
    ) {
        UserGoogleCredentialsTable.insert {
            it[UserGoogleCredentialsTable.userUuid] = userUuid.value
            it[UserGoogleCredentialsTable.googleSubject] = googleSubject
            it[UserGoogleCredentialsTable.accessToken] = PrefixTokenCipher.encrypt(accessToken)
            it[UserGoogleCredentialsTable.refreshToken] = PrefixTokenCipher.encrypt(refreshToken)
            it[UserGoogleCredentialsTable.accessTokenExpiresAt] = expiresAt
            it[UserGoogleCredentialsTable.grantedScopes] = "https://www.googleapis.com/auth/calendar.events"
        }
    }
}

private object PrefixTokenCipher : TokenCipher {
    override fun encrypt(plainText: String): String = "encrypted:$plainText"

    override fun decrypt(encodedCipherText: String): String =
        encodedCipherText.removePrefix("encrypted:")
}
