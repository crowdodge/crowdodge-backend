package com.crowdodge.congestion.domain.error

import com.crowdodge.shared.kernel.DomainError

/** 混雑 BC で扱うエラー。 */
sealed interface CongestionError : DomainError {
    /** 混雑予測を生成できなかったエラー。 */
    sealed interface GenerationError : CongestionError {
        data object GenerationSourceNotFound : GenerationError {
            override val code: String = "CONGESTION_GENERATION_SOURCE_NOT_FOUND"
        }

        data object GenerationInputChanged : GenerationError {
            override val code: String = "CONGESTION_GENERATION_INPUT_CHANGED"
        }
    }

    /** 外部サービスとの通信または応答に起因するエラー。 */
    sealed interface ExternalError : GenerationError {
        data object GenerationTemporarilyUnavailable : ExternalError {
            override val code: String = "CONGESTION_GENERATION_TEMPORARILY_UNAVAILABLE"
        }

        data object GenerationRejected : ExternalError {
            override val code: String = "CONGESTION_GENERATION_REJECTED"
        }
    }

    /** 混雑予測の値が不変条件を満たさないエラー。 */
    sealed interface ValidationError : CongestionError {
        val name: String

        data object InvalidCongestionPeriodRange : ValidationError {
            override val name: String = "period"
            override val code: String = "INVALID_CONGESTION_PERIOD_RANGE"
        }

        data object BlankCongestionArea : ValidationError {
            override val name: String = "area"
            override val code: String = "BLANK_CONGESTION_AREA"
        }

        data object BlankCongestionDescription : ValidationError {
            override val name: String = "description"
            override val code: String = "BLANK_CONGESTION_DESCRIPTION"
        }

        data object TooManyCongestionPeriods : ValidationError {
            override val name: String = "periods"
            override val code: String = "TOO_MANY_CONGESTION_PERIODS"
        }

        data object InvalidGenerationInputHash : ValidationError {
            override val name: String = "generation_input_hash"
            override val code: String = "INVALID_GENERATION_INPUT_HASH"
        }
    }
}
