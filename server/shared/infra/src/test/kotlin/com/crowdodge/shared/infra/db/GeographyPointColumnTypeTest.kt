package com.crowdodge.shared.infra.db

import com.crowdodge.shared.kernel.Location
import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.testcontainers.DockerClientFactory
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/**
 * [GeographyPointColumnType] の往復結合テスト（§13 infrastructure / Testcontainers + 実 PostGIS）。
 * 書き込み（CAST 付き bind）・読み出し（EWKB デコード）が ColumnType 内で完結し、[Location] に戻ることを検証する。
 * Docker 未起動の環境では登録せずスキップする。
 */
class GeographyPointColumnTypeTest : FunSpec() {
    private object Spots : Table("spots") {
        val id = integer("id")
        val point = geographyPoint("point")
        override val primaryKey = PrimaryKey(id)
    }

    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            install(TestContainerSpecExtension(postgres))

            test("Location は geography 列へ書き込み、同じ値で読み戻せる（往復）") {
                R2dbcFactory.connect(
                    DatabaseConfig(
                        host = postgres.host,
                        port = postgres.firstMappedPort,
                        database = postgres.databaseName,
                        username = postgres.username,
                        password = postgres.password,
                    ),
                ).use { conn ->
                    val osaka = Location(longitude = 135.5, latitude = 34.7)
                    val read = suspendTransaction(db = conn.database) {
                        SchemaUtils.create(Spots)
                        Spots.insert {
                            it[id] = 1
                            it[point] = osaka
                        }
                        Spots.selectAll().where { Spots.id eq 1 }.firstOrNull()?.get(Spots.point)
                    }
                    read shouldBe osaka
                }
            }

            test("負の座標・境界値も往復で保たれる") {
                R2dbcFactory.connect(
                    DatabaseConfig(
                        host = postgres.host,
                        port = postgres.firstMappedPort,
                        database = postgres.databaseName,
                        username = postgres.username,
                        password = postgres.password,
                    ),
                ).use { conn ->
                    val p = Location(longitude = -73.985, latitude = 40.748)
                    val read = suspendTransaction(db = conn.database) {
                        SchemaUtils.create(Spots)
                        Spots.insert {
                            it[id] = 2
                            it[point] = p
                        }
                        Spots.selectAll().where { Spots.id eq 2 }.firstOrNull()?.get(Spots.point)
                    }
                    read shouldBe p
                }
            }
        }
    }
}
