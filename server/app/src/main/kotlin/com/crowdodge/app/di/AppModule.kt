package com.crowdodge.app.di

import com.crowdodge.app.calendar.CalendarInitialSyncRequestedHandler
import com.crowdodge.app.calendar.MaintainGoogleCalendarSyncCoordinator
import com.crowdodge.app.calendar.ReplaceGoogleCalendarSelectionCoordinator
import com.crowdodge.app.calendar.UserCalendarConnectionAdapter
import com.crowdodge.app.calendar.googleCalendarConfig
import com.crowdodge.app.calendar.googleOAuthConfig
import com.crowdodge.app.calendar.googleTokenEncryptionKey
import com.crowdodge.app.calendar.jwtAppTokenConfig
import com.crowdodge.app.db.databaseConfig
import com.crowdodge.app.notification.EventNotificationScheduleHandler
import com.crowdodge.event.application.port.CalendarConnectionProvider
import com.crowdodge.event.di.eventModule
import com.crowdodge.event.infrastructure.google.GoogleCalendarConfig
import com.crowdodge.notification.application.port.RegistrationReadModelPort
import com.crowdodge.notification.di.notificationModule
import com.crowdodge.readmodel.notification.ExposedNotificationReadModel
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.DatabaseReadinessProbe
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcConnection
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.infra.messaging.TransactionalInProcessDomainEventPublisher
import com.crowdodge.shared.kernel.DomainEventHandler
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.ReadinessProbe
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.user.application.port.JwtAppTokenConfig
import com.crowdodge.user.application.port.TokenCipher
import com.crowdodge.user.di.userModule
import com.crowdodge.user.infrastructure.google.GoogleOAuthConfig
import com.crowdodge.user.infrastructure.security.AesGcmTokenCipher
import com.crowdodge.user.infrastructure.security.hmacAlgorithm
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.server.application.ApplicationEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Clock

/**
 * アプリ全体の Koin モジュール。
 * 共有基盤、設定、BC 間 ACL を配線し、各 BC が所有する DI モジュールを束ねる。
 */
fun appModule(environment: ApplicationEnvironment): Module {
    val databaseConfig = environment.databaseConfig()
    val googleOAuthConfig = environment.googleOAuthConfig()
    val googleCalendarConfig = environment.googleCalendarConfig()
    val jwtAppTokenConfig = environment.jwtAppTokenConfig().also { it.hmacAlgorithm() }
    val tokenCipher = AesGcmTokenCipher(environment.googleTokenEncryptionKey())

    return module {
        includes(userModule(googleCalendarConfig.apiBaseUrl), eventModule(), notificationModule())

        single<DatabaseConfig> { databaseConfig }
        // Koin 停止（ApplicationStopping）時に onClose でプールを破棄する。
        single<R2dbcConnection> { R2dbcFactory.connect(get()) } onClose { it?.close() }
        single<R2dbcDatabase> { get<R2dbcConnection>().database }
        single<TransactionRunner> { ExposedTransactionRunner(get()) }
        // readiness（/ready）用 DB 到達性プローブ。R2DBC は遅延接続のためここで初接続する。
        single<ReadinessProbe> { DatabaseReadinessProbe(get()) }
        single<CoroutineScope> { CoroutineScope(SupervisorJob() + Dispatchers.Default) } onClose { it?.cancel() }
        single<Clock> { Clock.System }
        // 登録系通知スケジュール作成用の BC 横断読み取り。
        single { ExposedNotificationReadModel(get()) }
        single<RegistrationReadModelPort> { get<ExposedNotificationReadModel>() }
        single<DomainEventHandler>(qualifier = named("calendarInitialSync")) {
            CalendarInitialSyncRequestedHandler(get())
        }
        single<DomainEventHandler>(qualifier = named("eventNotificationSchedule")) {
            EventNotificationScheduleHandler(get(), get(), get())
        }
        single<DomainEventPublisher> {
            TransactionalInProcessDomainEventPublisher(
                handlerProvider = { getAll<DomainEventHandler>() },
                scope = get(),
            )
        }
        single<HttpClient> {
            HttpClient(CIO) {
                install(HttpTimeout)
            }
        } onClose { it?.close() }

        single<GoogleOAuthConfig> { googleOAuthConfig }
        single<GoogleCalendarConfig> { googleCalendarConfig }
        single<JwtAppTokenConfig> { jwtAppTokenConfig }
        single<TokenCipher> { tokenCipher }
        single<CalendarConnectionProvider> { UserCalendarConnectionAdapter(get()) }
        single { ReplaceGoogleCalendarSelectionCoordinator(get(), get()) }
        single { MaintainGoogleCalendarSyncCoordinator(get(), get()) }
    }
}
