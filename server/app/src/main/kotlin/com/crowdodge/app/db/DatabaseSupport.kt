package com.crowdodge.app.db

import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.DatabaseSslMode
import io.ktor.server.application.ApplicationEnvironment

/** application.conf の `crowdodge.database` から [DatabaseConfig] を構築する。 */
fun ApplicationEnvironment.databaseConfig(): DatabaseConfig {
    val c = config.config("crowdodge.database")
    val sslMode = DatabaseSslMode.fromConfig(c.property("sslMode").getString())
    return DatabaseConfig(
        host = c.property("host").getString(),
        port = c.property("port").getString().toInt(),
        database = c.property("name").getString(),
        username = c.property("username").getString(),
        password = c.property("password").getString(),
        sslMode = sslMode,
        pgbouncer = c.property("pgbouncer").getString().toBooleanStrict(),
    )
}
