package com.crowdodge.app.db

import com.crowdodge.shared.infra.db.DatabaseConfig
import io.ktor.server.application.ApplicationEnvironment

/** application.conf の `crowdodge.database` から [DatabaseConfig] を構築する。 */
fun ApplicationEnvironment.databaseConfig(): DatabaseConfig {
    val c = config.config("crowdodge.database")
    return DatabaseConfig(
        host = c.property("host").getString(),
        port = c.property("port").getString().toInt(),
        database = c.property("name").getString(),
        username = c.property("username").getString(),
        password = c.property("password").getString(),
    )
}
