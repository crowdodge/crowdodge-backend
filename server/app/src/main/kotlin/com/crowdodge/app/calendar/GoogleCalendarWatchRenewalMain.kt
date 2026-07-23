package com.crowdodge.app.calendar

import com.crowdodge.app.di.appModule
import com.crowdodge.event.application.service.GoogleCalendarSyncLifecycleService
import com.crowdodge.user.application.service.UserCalendarSelectionService
import com.typesafe.config.ConfigFactory
import io.ktor.server.config.HoconApplicationConfig
import io.ktor.server.engine.applicationEnvironment
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.logger.slf4jLogger
import org.slf4j.LoggerFactory
import kotlin.system.exitProcess

fun main() {
    exitProcess(
        runBlocking {
            runGoogleCalendarWatchRenewal {
                val environment = applicationEnvironment {
                    config = HoconApplicationConfig(ConfigFactory.load())
                }
                val koinApplication = startKoin {
                    slf4jLogger()
                    modules(appModule(environment))
                }
                try {
                    val koin = koinApplication.koin
                    MaintainGoogleCalendarSyncCoordinator(
                        selections = koin.get<UserCalendarSelectionService>(),
                        syncs = koin.get<GoogleCalendarSyncLifecycleService>(),
                    ).execute()
                } finally {
                    koinApplication.close()
                }
            }
        },
    )
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun runGoogleCalendarWatchRenewal(
    execute: suspend () -> MaintenanceResult,
): Int {
    val logger = LoggerFactory.getLogger("GoogleCalendarWatchRenewalMain")
    return try {
        val result = execute()
        if (result.failed > 0) {
            logger.warn(
                "Google Calendar watch renewal completed with calendar-level failures: succeeded={}, failed={}",
                result.succeeded,
                result.failed,
            )
        } else {
            logger.info("Google Calendar watch renewal completed: succeeded={}", result.succeeded)
        }
        EXIT_SUCCESS
    } catch (error: Throwable) {
        logger.error("Google Calendar watch renewal failed before maintenance could complete", error)
        EXIT_FAILURE
    }
}

private const val EXIT_SUCCESS = 0
private const val EXIT_FAILURE = 1
