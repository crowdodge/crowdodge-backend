package com.crowdodge.app.notification

import com.crowdodge.notification.application.port.CongestionInfo
import com.crowdodge.notification.application.port.CongestionInfoPort
import com.crowdodge.notification.domain.model.EventUuid

/**
 * congestion BC 実装までの暫定実装（常に空 Map = 混雑情報なし）。
 * congestion BC 実装時にこのアダプタを差し替える。
 */
object PendingCongestionInfoAdapter : CongestionInfoPort {
    override suspend fun findAll(eventUuids: List<EventUuid>): Map<EventUuid, CongestionInfo> = emptyMap()
}
