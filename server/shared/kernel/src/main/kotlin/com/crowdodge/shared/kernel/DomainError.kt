package com.crowdodge.shared.kernel

/**
 * 全 BC 共通のドメインエラー基底（§10）。
 * 各 BC は自前の `sealed interface XxxError : DomainError` を定義し、
 * presentation 層で Problem(RFC9457) に変換する。
 */
interface DomainError {
    val code: String
}
