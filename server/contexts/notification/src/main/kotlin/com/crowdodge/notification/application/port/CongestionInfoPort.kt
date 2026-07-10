package com.crowdodge.notification.application.port

import com.crowdodge.notification.domain.model.EventUuid

data class CongestionInfo(val description: String)

/** 予定に紐づく混雑情報（バルク取得）。congestion BC 実装までは空 Map 固定の暫定実装（app 層）。 */
fun interface CongestionInfoPort {
    suspend fun findAll(eventUuids: List<EventUuid>): Map<EventUuid, CongestionInfo>
}
