package com.crowdodge.user.infrastructure.db

import arrow.core.raise.either
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import com.crowdodge.user.infrastructure.persistence.UsersTable
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.uuid.Uuid

class ExposedUserCalendarRepositoryTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("findAllとユーザー単位の一括置換を永続化する") {
                R2dbcFactory.connect(postgres.databaseConfig()).use { connection ->
                    val firstUser = UserUuid(Uuid.parse("30000000-0000-0000-0000-000000000011"))
                    val secondUser = UserUuid(Uuid.parse("30000000-0000-0000-0000-000000000012"))
                    val repository = ExposedUserCalendarRepository()

                    suspendTransaction(db = connection.database) {
                        SchemaUtils.drop(UserCalendarsTable, UsersTable)
                        SchemaUtils.create(UsersTable, UserCalendarsTable)
                        insertUser(firstUser, "first@example.com", "first-google")
                        insertUser(secondUser, "second@example.com", "second-google")
                        repository.create(calendar(firstUser, "old"))
                        repository.create(calendar(secondUser, "other"))

                        repository.replaceForUser(
                            firstUser,
                            listOf(calendar(firstUser, "one"), calendar(firstUser, "two")),
                        )

                        repository.findByUserUuid(firstUser).map { it.googleCalendarId.value } shouldContainExactly
                            listOf("one", "two")
                        repository.findAll().map { it.googleCalendarId.value }.toSet() shouldContainExactly
                            setOf("one", "two", "other")
                    }
                }
            }

            test("重複登録と重複を含む一括置換はDuplicateCalendarを返す") {
                R2dbcFactory.connect(postgres.databaseConfig()).use { connection ->
                    val userUuid = UserUuid(Uuid.parse("30000000-0000-0000-0000-000000000021"))
                    val repository = ExposedUserCalendarRepository()

                    suspendTransaction(db = connection.database) {
                        SchemaUtils.drop(UserCalendarsTable, UsersTable)
                        SchemaUtils.create(UsersTable, UserCalendarsTable)
                        insertUser(userUuid, "duplicate@example.com", "duplicate-google")

                        repository.create(calendar(userUuid, "one")).isRight() shouldBe true
                        repository.create(calendar(userUuid, "one")).leftOrNull() shouldBe
                            com.crowdodge.user.domain.error.UserError.ConflictError.DuplicateCalendar

                        repository.replaceForUser(
                            userUuid,
                            listOf(calendar(userUuid, "two"), calendar(userUuid, "two")),
                        ).leftOrNull() shouldBe
                            com.crowdodge.user.domain.error.UserError.ConflictError.DuplicateCalendar
                    }
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

    private suspend fun insertUser(userUuid: UserUuid, email: String, googleId: String) {
        UsersTable.insert {
            it[UsersTable.userUuid] = userUuid.value
            it[UsersTable.email] = email
            it[UsersTable.googleId] = googleId
        }
    }

    private fun calendar(userUuid: UserUuid, id: String): UserCalendar = either {
        UserCalendar.select(userUuid, googleCalendarId(id))
    }.getOrNull()!!
}
