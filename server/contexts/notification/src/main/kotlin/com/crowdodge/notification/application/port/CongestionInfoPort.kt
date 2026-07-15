package com.crowdodge.notification.application.port

import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.notification.domain.model.EventUuid

data class CongestionInfo(val description: String)

sealed interface CongestionInfoResult {
    data class Success(val info: CongestionInfo?) : CongestionInfoResult

    data class Failure(val error: NotificationError.CongestionInfoError) : CongestionInfoResult
}

/** 予定に紐づく混雑情報を取得し、要求されたすべての予定UUIDについて成功または失敗を返すバルク取得Port。 */
fun interface CongestionInfoPort {
    suspend fun findAll(eventUuids: List<EventUuid>): Map<EventUuid, CongestionInfoResult>
}
