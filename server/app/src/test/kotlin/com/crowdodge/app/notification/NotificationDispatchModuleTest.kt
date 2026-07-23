package com.crowdodge.app.notification

import arrow.core.right
import com.crowdodge.notification.application.dispatch.DispatchDueNotificationsUseCase
import com.crowdodge.notification.application.port.PushNotificationSender
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class NotificationDispatchModuleTest : FunSpec({
    test("通知Job専用moduleは最小設定で DispatchDueNotificationsUseCase を解決できる") {
        val koinApplication = koinApplication {
            allowOverride(true)
            modules(
                notificationDispatchModule(notificationDispatchEnvironment()),
                module {
                    single<PushNotificationSender> {
                        PushNotificationSender { messages -> messages.map { Unit.right() } }
                    }
                },
            )
        }

        try {
            koinApplication.koin.get<DispatchDueNotificationsUseCase>()
                .shouldBeInstanceOf<DispatchDueNotificationsUseCase>()
        } finally {
            koinApplication.close()
        }
    }
})

private fun notificationDispatchEnvironment() = applicationEnvironment {
    config = MapApplicationConfig(
        "crowdodge.database.host" to "localhost",
        "crowdodge.database.port" to "5432",
        "crowdodge.database.name" to "crowdodge",
        "crowdodge.database.username" to "crowdodge",
        "crowdodge.database.password" to "crowdodge",
        "crowdodge.database.sslMode" to "disable",
        "crowdodge.database.pgbouncer" to "false",
        "crowdodge.congestion.gemini.apiBaseUrl" to "http://localhost",
        "crowdodge.congestion.gemini.apiKey" to "test-key",
        "crowdodge.congestion.gemini.maxConcurrency" to "10",
    )
}
