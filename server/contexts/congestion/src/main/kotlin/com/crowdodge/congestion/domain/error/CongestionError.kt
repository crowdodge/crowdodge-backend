package com.crowdodge.congestion.domain.error

import com.crowdodge.shared.kernel.DomainError

/** 混雑 BC で扱うエラー。 */
sealed interface CongestionError : DomainError {
    /** 混雑予測を生成できなかったエラー。 */
    sealed interface GenerationError : CongestionError {
        /** 予定・目的地・経路から生成元を構成できなかったことを表す。 */
        data object GenerationSourceNotFound : GenerationError {
            override val code: String = "CONGESTION_GENERATION_SOURCE_NOT_FOUND"
        }

        /** 予測生成中に生成入力が変更されたことを表す。 */
        data object GenerationInputChanged : GenerationError {
            override val code: String = "CONGESTION_GENERATION_INPUT_CHANGED"
        }
    }

    /** 外部サービスとの通信または応答に起因するエラー。 */
    sealed interface ExternalError : GenerationError {
        /** 後続の実行で回復する可能性がある外部サービス障害。 */
        data object GenerationTemporarilyUnavailable : ExternalError {
            override val code: String = "CONGESTION_GENERATION_TEMPORARILY_UNAVAILABLE"
        }

        /** 外部サービスの応答を有効な予測として採用できないことを表す。 */
        data object GenerationRejected : ExternalError {
            override val code: String = "CONGESTION_GENERATION_REJECTED"
        }
    }

    /** 混雑予測の値が不変条件を満たさないエラー。 */
    sealed interface ValidationError : CongestionError {
        /** 不正な値を持つ項目名。 */
        val name: String

        /** 混雑期間の開始と終了の前後関係が不正であることを表す。 */
        data object InvalidCongestionPeriodRange : ValidationError {
            override val name: String = "period"
            override val code: String = "INVALID_CONGESTION_PERIOD_RANGE"
        }

        /** 混雑エリアが空であることを表す。 */
        data object BlankCongestionArea : ValidationError {
            override val name: String = "area"
            override val code: String = "BLANK_CONGESTION_AREA"
        }

        /** 混雑説明が空であることを表す。 */
        data object BlankCongestionDescription : ValidationError {
            override val name: String = "description"
            override val code: String = "BLANK_CONGESTION_DESCRIPTION"
        }

        /** 一つの予測に含まれる混雑期間が上限を超えたことを表す。 */
        data object TooManyCongestionPeriods : ValidationError {
            override val name: String = "periods"
            override val code: String = "TOO_MANY_CONGESTION_PERIODS"
        }

        /** 生成入力ハッシュの形式が不正であることを表す。 */
        data object InvalidGenerationInputHash : ValidationError {
            override val name: String = "generation_input_hash"
            override val code: String = "INVALID_GENERATION_INPUT_HASH"
        }
    }
}
