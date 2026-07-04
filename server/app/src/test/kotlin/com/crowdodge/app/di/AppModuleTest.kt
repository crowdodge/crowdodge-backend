package com.crowdodge.app.di

import com.crowdodge.app.calendar.MaintainGoogleCalendarSyncCoordinator
import com.crowdodge.event.application.service.GoogleCalendarSyncLifecycleService
import com.crowdodge.shared.infra.messaging.TransactionalInProcessDomainEventPublisher
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.user.application.query.ProxyGoogleCalendarUseCase
import com.crowdodge.user.application.service.UserCalendarSelectionService
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import org.koin.dsl.koinApplication
import org.koin.dsl.module

class AppModuleTest : FunSpec({
    test("Google Calendar watch renewalのDI構成を解決できる") {
        val koinApplication = koinApplication {
            allowOverride(true)
            modules(
                appModule(appEnvironment()),
                module {
                    single<TransactionRunner> { ImmediateTransactionRunner }
                },
            )
        }

        try {
            val koin = koinApplication.koin
            koin.get<DomainEventPublisher>()
                .shouldBeInstanceOf<TransactionalInProcessDomainEventPublisher>()
            koin.get<UserCalendarSelectionService>()
                .shouldBeInstanceOf<UserCalendarSelectionService>()
            koin.get<GoogleCalendarSyncLifecycleService>()
                .shouldBeInstanceOf<GoogleCalendarSyncLifecycleService>()
            koin.get<MaintainGoogleCalendarSyncCoordinator>()
                .shouldBeInstanceOf<MaintainGoogleCalendarSyncCoordinator>()
            koin.get<ProxyGoogleCalendarUseCase>()
                .shouldBeInstanceOf<ProxyGoogleCalendarUseCase>()
        } finally {
            koinApplication.close()
        }
    }
})

private object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private fun appEnvironment() = applicationEnvironment {
    config = MapApplicationConfig(
        "crowdodge.database.host" to "localhost",
        "crowdodge.database.port" to "5432",
        "crowdodge.database.name" to "crowdodge",
        "crowdodge.database.username" to "crowdodge",
        "crowdodge.database.password" to "crowdodge",
        "crowdodge.database.sslMode" to "disable",
        "crowdodge.database.pgbouncer" to "false",
        "crowdodge.googleCalendar.apiBaseUrl" to "https://www.googleapis.com",
        "crowdodge.googleCalendar.webhookUrl" to "https://example.test/webhooks/google-calendar",
        "crowdodge.googleCalendar.channelToken" to "channel-token",
        "crowdodge.googleCalendar.fullSyncWindowDays" to "90",
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
