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
    )
}
