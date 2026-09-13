package com.crowdodge.distination.domain.aggregates.eventdestination

class EventDestination(
    val id: EventDestinationId,
    val recurringScheduleId: RecurringScheduleId?,
    val destination: Destination,
    val duration: Duration,
    val routeInformation: RouteInformation,
) {
    // TODO: 一旦後で
}