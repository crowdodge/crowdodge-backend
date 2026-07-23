@file:Suppress("Filename", "MatchingDeclarationName")

package com.crowdodge.user.domain.event

import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.user.domain.model.UserCalendarUuid
import kotlin.time.Instant

data class CalendarInitialSyncRequested(
    val userCalendarUuid: UserCalendarUuid,
    override val occurredAt: Instant,
) : DomainEvent
