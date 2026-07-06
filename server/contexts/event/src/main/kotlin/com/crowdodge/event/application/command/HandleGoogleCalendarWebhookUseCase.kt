package com.crowdodge.event.application.command

import arrow.core.Either
import arrow.core.raise.either
import com.crowdodge.event.application.port.CalendarSyncStatePort
import com.crowdodge.event.application.service.GoogleCalendarEventSynchronizer
import com.crowdodge.event.domain.error.EventError
import com.crowdodge.shared.kernel.TransactionRunner

class HandleGoogleCalendarWebhookUseCase(
    private val states: CalendarSyncStatePort,
    private val synchronizer: GoogleCalendarEventSynchronizer,
    private val transactions: TransactionRunner,
) {
    suspend fun execute(
        channelId: String,
        channelToken: String?,
        resourceState: String,
    ): Either<EventError, Unit> =
        either {
            if (resourceState != RESOURCE_STATE_EXISTS) return@either

            val state = transactions.readOnly {
                states.findByChannelId(channelId)
            } ?: return@either
            if (state.watchChannelToken != channelToken) {
                return@either
            }

            synchronizer.incrementalSync(state.userCalendarUuid).bind()
        }

    private companion object {
        private const val RESOURCE_STATE_EXISTS = "exists"
    }
}
