package com.crowdodge.app.migration

import org.slf4j.LoggerFactory

/**
 * マイグレーション適用ツール（Flyway を単体実行して終了する）。
 *
 * Ktor アプリ本体（EngineMain）とは別プロセス——init container や
 * デプロイ前ジョブ——として実行し、起動とマイグレーションのライフサイクルを分離する。
 *
 *   ./gradlew :app:flywayMigrate
 *   java -cp app-all.jar com.crowdodge.app.migration.MigrateMainKt
 *
 * 設定は application.conf（環境変数で上書き可）をそのまま読む。
 */
fun main() {
    val log = LoggerFactory.getLogger("MigrateMain")
    val config = loadDatabaseConfig()
    log.info("Flyway マイグレーションを実行します (jdbc={})", jdbcUrl(config))
    FlywayMigrator.migrate(config)
    log.info("マイグレーション完了")
}
