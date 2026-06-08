package com.crowdodge.shared.infra.db

import io.r2dbc.pool.ConnectionPool
import io.r2dbc.pool.ConnectionPoolConfiguration
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import org.jetbrains.exposed.v1.core.vendors.PostgreSQLDialect
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabaseConfig

/**
 * Exposed の [R2dbcDatabase] と接続プールを束ねる。
 * アプリ停止時に [close] でプールを破棄し、コネクションリークを防ぐ（§13）。
 */
class R2dbcConnection(
    val database: R2dbcDatabase,
    private val pool: ConnectionPool,
) : AutoCloseable {
    override fun close() {
        pool.dispose()
    }
}

/**
 * r2dbc-pool 経由でノンブロッキング接続を確立する（§1/§12）。HikariCP は使わない。
 *
 * 認証情報は URL に埋め込まず、ConnectionFactoryOptions の USER/PASSWORD で分離して渡す
 * （ログ漏洩・特殊文字パース破綻の回避）。委譲先ドライバ（postgresql）の ConnectionFactory を
 * [ConnectionPool] でラップしてプール化する。
 */
object R2dbcFactory {
    fun connect(config: DatabaseConfig): R2dbcConnection {
        val options = ConnectionFactoryOptions.builder()
            .option(ConnectionFactoryOptions.DRIVER, "postgresql")
            .option(ConnectionFactoryOptions.HOST, config.host)
            .option(ConnectionFactoryOptions.PORT, config.port)
            .option(ConnectionFactoryOptions.DATABASE, config.database)
            .option(ConnectionFactoryOptions.USER, config.username)
            .option(ConnectionFactoryOptions.PASSWORD, config.password)
            .build()
        val pool = ConnectionPool(
            ConnectionPoolConfiguration.builder(ConnectionFactories.get(options)).build(),
        )
        // ConnectionPool 自体が ConnectionFactory。Exposed には pool を渡してプールを使わせる。
        // 接続未確立の起動時点では dialect を解決できないため explicitDialect を明示する。
        val database = R2dbcDatabase.connect(
            connectionFactory = pool,
            databaseConfig = R2dbcDatabaseConfig.Builder().apply {
                explicitDialect = PostgreSQLDialect()
            },
        )
        return R2dbcConnection(database, pool)
    }
}
