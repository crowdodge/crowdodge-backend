package com.crowdodge.app.migration

import com.crowdodge.shared.infra.db.DatabaseConfig
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationInfo

/**
 * Flyway は R2DBC 非対応のため、マイグレーションのみ JDBC 接続で実行する（§12）。
 * マイグレーションファイルは `db/migration/Vxxx__*.sql`（app の resources）。
 * JDBC/Flyway は migration 専用なので app に閉じる（shared/infra は R2DBC 専用）。
 */
object FlywayMigrator {
    fun migrate(config: DatabaseConfig) {
        Flyway.configure()
            .dataSource(jdbcUrl(config), config.username, config.password)
            .locations("classpath:db/migration")
            .load()
            .migrate()
    }

    fun pending(config: DatabaseConfig): Array<MigrationInfo> {
        return Flyway.configure()
            .dataSource(jdbcUrl(config), config.username, config.password)
            .locations("classpath:db/migration")
            .load()
            .info()
            .pending()
    }
}
