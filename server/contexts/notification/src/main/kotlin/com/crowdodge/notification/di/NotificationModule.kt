package com.crowdodge.notification.di

import com.crowdodge.notification.application.command.CancelNotificationUseCase
import com.crowdodge.notification.application.command.RegisterNotificationSchedulesUseCase
import com.crowdodge.notification.application.command.RescheduleNotificationUseCase
import com.crowdodge.notification.domain.repository.NotificationScheduleRepository
import com.crowdodge.notification.infrastructure.db.ExposedNotificationScheduleRepository
import org.koin.dsl.module

fun notificationModule() = module {
    single<NotificationScheduleRepository> { ExposedNotificationScheduleRepository() }
    single<RegisterNotificationSchedulesUseCase> {
        RegisterNotificationSchedulesUseCase(get(), get(), get(), get())
    }
    single<RescheduleNotificationUseCase> {
        RescheduleNotificationUseCase(get(), get(), get(), get())
    }
    single<CancelNotificationUseCase> { CancelNotificationUseCase(get(), get()) }
}
