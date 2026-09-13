package com.crowdodge.distination.domain.values

import com.crowdodge.distination.domain.aggregates.eventdestination.RecurringScheduleId
import com.crowdodge.shared.kernel.UserUuid

// HACK: VOで良い可能性
// HACK: 理由はUseCaseでのみ使われるもの（予定作成・更新時にUseCase内で作成し、推定処理に流す入力値っぽい）だから

// HACK: ユビキタス言語的にはScheduleではなくEventが正しいかも。あとでIssue投げるか検討、いまはこれで行く。

class Schedule(
    val id: ScheduleId,
    val recurringScheduleId: RecurringScheduleId?,// 繰り返し予定に対して、それぞれで処理しないための紐付け
    val ownerId: UserUuid,
    val title: String,
    val destinationHint: DestinationHint, // この段階では文字列で持つ。BCの推定ロジック側で[郵便番号・住所・緯度経度・その他（判別不能）]などは判定する。
) {
}