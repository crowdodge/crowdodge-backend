package com.crowdodge.app.notification

import com.crowdodge.congestion.application.service.GenerateCongestionInfoUseCase
import com.crowdodge.notification.application.port.CongestionInfo
import com.crowdodge.notification.application.port.CongestionInfoPort
import com.crowdodge.notification.application.port.CongestionInfoResult
import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.congestion.application.service.CongestionInfoResult as CongestionResult
import com.crowdodge.congestion.domain.error.CongestionError as CongestionDomainError
import com.crowdodge.congestion.domain.model.EventUuid as CongestionEventUuid
import com.crowdodge.notification.domain.model.EventUuid as NotificationEventUuid

/** 通知 BC と混雑 BC の UUID・結果型を相互変換する。 */
class CongestionInfoAdapter(
    private val generate: GenerateCongestionInfoUseCase,
) : CongestionInfoPort {
    override suspend fun findAll(
        eventUuids: List<NotificationEventUuid>,
    ): Map<NotificationEventUuid, CongestionInfoResult> {
        val congestionEventUuids = eventUuids.mapTo(mutableSetOf()) { eventUuid ->
            CongestionEventUuid(eventUuid.value)
        }
        val congestionResults = generate.execute(congestionEventUuids)

        return buildMap(congestionResults.size) {
            congestionResults.forEach { (eventUuid, result) ->
                put(NotificationEventUuid(eventUuid.value), result.toNotificationResult())
            }
        }
    }

    private fun CongestionResult.toNotificationResult(): CongestionInfoResult = when (this) {
        is CongestionResult.Success -> CongestionInfoResult.Success(
            summary?.let { CongestionInfo(it.description) },
        )

        is CongestionResult.Failure -> CongestionInfoResult.Failure(
            // BC 間でエラー型を共有すると、通知 BC の再試行方針が混雑 BC の実装詳細に依存するため共有しない。
            when (error) {
                CongestionDomainError.ExternalError.GenerationTemporarilyUnavailable,
                CongestionDomainError.GenerationError.GenerationInputChanged,
                -> NotificationError.CongestionInfoError.TemporarilyUnavailable

                CongestionDomainError.ExternalError.GenerationRejected,
                CongestionDomainError.GenerationError.GenerationSourceNotFound,
                -> NotificationError.CongestionInfoError.PermanentlyUnavailable
            },
        )
    }
}
