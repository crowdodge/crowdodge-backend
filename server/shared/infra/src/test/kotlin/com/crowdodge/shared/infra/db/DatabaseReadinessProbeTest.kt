package com.crowdodge.shared.infra.db

import io.kotest.core.extensions.install
import io.kotest.core.spec.style.FunSpec
import io.kotest.extensions.testcontainers.TestContainerSpecExtension
import io.kotest.matchers.shouldBe
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import kotlin.time.Duration.Companion.seconds

/**
 * [DatabaseReadinessProbe] の結合テスト（§13 infrastructure / kotest-extensions-testcontainers）。
 * Docker 未起動の環境では到達ケースを登録せずスキップする（不通ケースは Docker 不要で常に実行）。
 * `install` は spec 設定 API のため class + init 形式で呼ぶ。
 */
class DatabaseReadinessProbeTest : FunSpec() {
    init {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            val postgres = PostgreSQLContainer(
                DockerImageName.parse("imresamu/postgis:18-3.6").asCompatibleSubstituteFor("postgres"),
            ).withDatabaseName("crowdodge").withUsername("crowdodge").withPassword("crowdodge")
            // spec ライフサイクルでコンテナの起動・破棄を管理する。
            install(TestContainerSpecExtension(postgres))

            test("DB 到達可能なら isReady は true") {
                R2dbcFactory.connect(
                    DatabaseConfig(
                        host = postgres.host,
                        port = postgres.firstMappedPort,
                        database = postgres.databaseName,
                        username = postgres.username,
                        password = postgres.password,
                    ),
                ).use { conn ->
                    DatabaseReadinessProbe(conn.database).isReady() shouldBe true
                }
            }
        }

        test("DB 不通なら isReady は false") {
            // 存在しないポートへ向け、接続不可で false になることを確認（Docker 不要）。
            R2dbcFactory.connect(
                DatabaseConfig(host = "localhost", port = 1, database = "absent", username = "u", password = "p"),
            ).use { conn ->
                DatabaseReadinessProbe(conn.database, timeout = 2.seconds).isReady() shouldBe false
            }
        }
    }
}
