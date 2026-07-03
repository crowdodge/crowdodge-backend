package com.crowdodge.event.di

import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.port.GoogleCalendarEventsGateway
import com.crowdodge.event.application.service.GoogleCalendarEventSynchronizer
import com.crowdodge.event.domain.repository.EventRepository
import com.crowdodge.event.infrastructure.db.ExposedEventRepository
import com.crowdodge.event.infrastructure.db.adapter.ExposedCalendarSyncStateAdapter
import com.crowdodge.event.infrastructure.db.datasource.ExposedEventCalendarSyncDataSource
import com.crowdodge.event.infrastructure.google.GoogleCalendarGateway
import org.koin.dsl.module

fun eventModule() = module {
    single<ExposedEventCalendarSyncDataSource> { ExposedEventCalendarSyncDataSource() }
    single<EventRepository> { ExposedEventRepository() }
    single<CalendarSyncStatePort> { ExposedCalendarSyncStateAdapter(get()) }
    single<GoogleCalendarGateway> { GoogleCalendarGateway(get(), get()) }
    single<GoogleCalendarEventsGateway> { get<GoogleCalendarGateway>() }
    single {
        GoogleCalendarEventSynchronizer(
            get(),
            get(),
            get(),
            get(),
            get(),
            get(),
        )
    }
}
