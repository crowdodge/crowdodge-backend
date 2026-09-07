package com.crowdodge.distination.domain.error

// HACK: ドメインエラーとしてcodeを持つべきではない。
// HACK: shard kernel側の実装の修正を検討
interface TentativeDomainError

sealed interface DestinationError : TentativeDomainError {
    // バリデーションエラー
    sealed interface ValidationError : DestinationError {
        // 予定IDが空
        data object BlankScheduleId : ValidationError

        // 繰り返し予定IDが空
        data object BlankRecurringScheduleId: ValidationError
    }
}