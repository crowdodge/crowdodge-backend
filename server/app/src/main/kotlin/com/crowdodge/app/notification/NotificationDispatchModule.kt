package com.crowdodge.app.notification

import com.crowdodge.app.db.databaseConfig
import com.crowdodge.notification.application.dispatch.DispatchDueNotificationsUseCase
import com.crowdodge.notification.application.port.CongestionInfoPort
import com.crowdodge.notification.application.port.DispatchReadModelPort
import com.crowdodge.notification.application.port.PushNotificationSender
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.notification.infrastructure.db.ExposedNotificationScheduleRepository
import com.crowdodge.notification.infrastructure.fcm.FcmPushNotificationSender
import com.crowdodge.readmodel.notification.ExposedNotificationReadModel
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcConnection
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.TransactionRunner
import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import io.ktor.server.application.ApplicationEnvironment
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.core.module.Module
import org.koin.dsl.module
import org.koin.dsl.onClose
import kotlin.time.Clock

fun notificationDispatchModule(environment: ApplicationEnvironment): Module {
    val databaseConfig = environment.databaseConfig()

    return module {
        single<DatabaseConfig> { databaseConfig }
        single<R2dbcConnection> { R2dbcFactory.connect(get()) } onClose { it?.close() }
        single<R2dbcDatabase> { get<R2dbcConnection>().database }
        single<TransactionRunner> { ExposedTransactionRunner(get()) }

        single<NotificationScheduleRepository> { ExposedNotificationScheduleRepository() }
        single<DispatchReadModelPort> { ExposedNotificationReadModel(get()) }
        single<CongestionInfoPort> { PendingCongestionInfoAdapter }

        single<FirebaseMessaging> {
            val app = FirebaseApp.getApps().firstOrNull() ?: FirebaseApp.initializeApp(
                FirebaseOptions.builder()
                    .setCredentials(GoogleCredentials.getApplicationDefault())
                    .build(),
            )
            FirebaseMessaging.getInstance(app)
        }
        single<PushNotificationSender> { FcmPushNotificationSender(get()) }
        single<Clock> { Clock.System }
        single { DispatchDueNotificationsUseCase(get(), get(), get(), get(), get(), get()) }
    }
}
