package com.crowdodge.app.di

import com.crowdodge.shared.infra.messaging.TransactionalInProcessDomainEventPublisher
import com.crowdodge.shared.kernel.DomainEventHandler
import com.crowdodge.shared.kernel.DomainEventPublisher
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import org.koin.dsl.koinApplication

class AppModuleTest : FunSpec({
    test("DomainEventPublisherを解決できる") {
        val koinApplication = koinApplication {
            modules(appModule(appEnvironment()))
        }

        try {
            val koin = koinApplication.koin
            koin.get<DomainEventPublisher>()
                .shouldBeInstanceOf<TransactionalInProcessDomainEventPublisher>()
            koin.getAll<DomainEventHandler>().shouldBeEmpty()
        } finally {
            koinApplication.close()
        }
    }
})

private fun appEnvironment() = applicationEnvironment {
    config = MapApplicationConfig(
        "crowdodge.database.host" to "localhost",
        "crowdodge.database.port" to "5432",
        "crowdodge.database.name" to "crowdodge",
        "crowdodge.database.username" to "crowdodge",
        "crowdodge.database.password" to "crowdodge",
        "crowdodge.database.sslMode" to "disable",
        "crowdodge.database.pgbouncer" to "false",
        "crowdodge.googleCalendar.oauthTokenUrl" to "https://oauth2.googleapis.com/token",
        "crowdodge.googleCalendar.oauthJwksUrl" to "https://www.googleapis.com/oauth2/v3/certs",
        "crowdodge.googleCalendar.oauthClientId" to "client-id",
        "crowdodge.googleCalendar.oauthClientSecret" to "",
        "crowdodge.googleCalendar.tokenEncryptionKey" to "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=",
        "crowdodge.auth.jwt.secret" to "0123456789abcdef0123456789abcdef",
        "crowdodge.auth.jwt.issuer" to "crowdodge-api",
        "crowdodge.auth.jwt.audience" to "crowdodge-app",
        "crowdodge.auth.jwt.accessTokenTtlSeconds" to "900",
        "crowdodge.auth.jwt.refreshTokenTtlSeconds" to "2592000",
    )
}
