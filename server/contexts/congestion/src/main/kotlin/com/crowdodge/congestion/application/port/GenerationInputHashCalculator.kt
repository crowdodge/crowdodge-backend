package com.crowdodge.congestion.application.port

/** 混雑生成入力の再利用判定用ハッシュを計算する。 */
fun interface GenerationInputHashCalculator {
    /** 指定した生成元の安定したハッシュを返す。 */
    fun calculate(source: CongestionGenerationSource): String
}
