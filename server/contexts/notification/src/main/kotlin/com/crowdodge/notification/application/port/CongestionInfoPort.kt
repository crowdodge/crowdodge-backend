package com.crowdodge.notification.application.port

import com.crowdodge.notification.domain.error.NotificationError
import com.crowdodge.notification.domain.model.EventUuid

/** 通知本文へ追加できる混雑情報。 */
data class CongestionInfo(val description: String)

/** 予定ごとの混雑情報取得結果。 */
sealed interface CongestionInfoResult {
    /** 混雑情報を取得できた結果。混雑なしの場合は [info] が null になる。 */
    data class Success(val info: CongestionInfo?) : CongestionInfoResult

    /** 混雑情報を取得できなかった結果。 */
    data class Failure(val error: NotificationError.CongestionInfoError) : CongestionInfoResult
}

/** 予定に紐づく混雑情報を一括取得する。 */
fun interface CongestionInfoPort {
    /** 要求されたすべての予定 UUID について成功または失敗を返す。 */
    suspend fun findAll(eventUuids: List<EventUuid>): Map<EventUuid, CongestionInfoResult>
}
