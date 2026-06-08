package com.crowdodge.shared.kernel

import java.time.Instant

/**
 * 全ドメインイベントの基底（§5 命名の約束）。
 * 素の `Event`（ユーザーの予定）と混同しないため、基底は必ず `DomainEvent`。
 * 具体名は業務的な過去形（EventScheduled / EventDestinationEstimated など）。
 */
interface DomainEvent {
    val occurredAt: Instant
}

/**
 * ドメインイベント発行ポート（被駆動）。application が依存し、
 * infrastructure が実装する（§9。配送の実装方式は未確定）。
 */
fun interface DomainEventPublisher {
    suspend fun publish(event: DomainEvent)
}
