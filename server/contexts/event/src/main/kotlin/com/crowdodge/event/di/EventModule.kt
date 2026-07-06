package com.crowdodge.event.di

import com.crowdodge.event.application.command.HandleGoogleCalendarWebhookUseCase
import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.port.CalendarWatchRegistrationGateway
import com.crowdodge.event.application.port.GoogleCalendarEventsGateway
import com.crowdodge.event.application.service.GoogleCalendarEventSynchronizer
import com.crowdodge.event.application.service.GoogleCalendarSyncLifecycleService
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.event.infrastructure.db.ExposedEventRepository
import com.crowdodge.event.infrastructure.db.adapter.ExposedCalendarSyncStateAdapter
import com.crowdodge.event.infrastructure.db.datasource.ExposedEventCalendarSyncDataSource
import com.crowdodge.event.infrastructure.google.GoogleCalendarConfig
import com.crowdodge.event.infrastructure.google.GoogleCalendarGateway
import org.koin.dsl.module

fun eventModule() = module {
    single<ExposedEventCalendarSyncDataSource> { ExposedEventCalendarSyncDataSource() }
    single<EventRepository> { ExposedEventRepository() }
    single<CalendarSyncStatePort> { ExposedCalendarSyncStateAdapter(get()) }
    single<GoogleCalendarGateway> { GoogleCalendarGateway(get(), get()) }
    single<GoogleCalendarEventsGateway> { get<GoogleCalendarGateway>() }
    single<CalendarWatchRegistrationGateway> { get<GoogleCalendarGateway>() }
    single<GoogleCalendarEventSynchronizer> {
        GoogleCalendarEventSynchronizer(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }
    single<GoogleCalendarSyncLifecycleService> {
        GoogleCalendarSyncLifecycleService(
            watches = get(),
            states = get(),
            events = get(),
            synchronizer = get(),
            connections = get(),
            transactions = get(),
            materializationWindowDays = get<GoogleCalendarConfig>().fullSyncWindowDays,
        )
    }
    single<HandleGoogleCalendarWebhookUseCase> { HandleGoogleCalendarWebhookUseCase(get(), get()) }
}
