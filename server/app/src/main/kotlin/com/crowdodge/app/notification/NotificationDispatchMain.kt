package com.crowdodge.app.notification

import com.crowdodge.notification.application.dispatch.DispatchDueNotificationsUseCase
import com.crowdodge.notification.application.dispatch.DispatchResult
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
            runNotificationDispatch {
                val environment = applicationEnvironment {
                    config = HoconApplicationConfig(ConfigFactory.load())
                }
                val koinApplication = startKoin {
                    slf4jLogger()
                    modules(notificationDispatchModule(environment))
                }
                try {
                    koinApplication.koin.get<DispatchDueNotificationsUseCase>().execute()
                } finally {
                    koinApplication.close()
                }
            }
        },
    )
}

@Suppress("TooGenericExceptionCaught")
internal suspend fun runNotificationDispatch(
    execute: suspend () -> DispatchResult,
): Int {
    val logger = LoggerFactory.getLogger("NotificationDispatchMain")
    return try {
        val result = execute()
        if (result.failed > 0) {
            logger.warn(
                "Notification dispatch completed with failures: completed={}, failed={}, canceled={}",
                result.completed,
                result.failed,
                result.canceled,
            )
        } else {
            logger.info(
                "Notification dispatch completed: completed={}, canceled={}",
                result.completed,
                result.canceled,
            )
        }
        EXIT_SUCCESS
    } catch (error: Throwable) {
        logger.error("Notification dispatch failed before completion", error)
        EXIT_FAILURE
    }
}

private const val EXIT_SUCCESS = 0
private const val EXIT_FAILURE = 1
