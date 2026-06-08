package com.crowdodge.app.migration

import com.crowdodge.shared.infra.db.DatabaseConfig
import com.typesafe.config.ConfigFactory

/**
 * マイグレーションツール（[MigrateMain] / [GenerateMigrationMain]）共通のユーティリティ。
 *
 * migration の main は Ktor も Koin(DI) も起動しない standalone プロセスのため、
 * application.conf（環境変数で上書き可）を自前で読み込んで [DatabaseConfig] を組み立てる。
 * JDBC は migration でのみ使うので、JDBC URL の組み立ても app に閉じる（shared/infra は R2DBC 専用）。
 */
fun loadDatabaseConfig(): DatabaseConfig {
    val c = ConfigFactory.load().getConfig("crowdodge.database")
    return DatabaseConfig(
        host = c.getString("host"),
        port = c.getInt("port"),
        database = c.getString("name"),
        username = c.getString("username"),
        password = c.getString("password"),
    )
}

/** Flyway / マイグレーション生成用の JDBC URL。認証情報は含めず別引数で渡す。 */
fun jdbcUrl(config: DatabaseConfig): String =
    "jdbc:postgresql://${config.host}:${config.port}/${config.database}"
