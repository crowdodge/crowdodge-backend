package com.crowdodge.shared.kernel

import java.util.UUID

/**
 * UUID ベース識別子の基底。主キーは `<単数テーブル名>_uuid`（§12 命名規約）。
 * 各 BC は固有の識別子（EventId / DestinationId など）をこの形で定義する。
 */
interface EntityId {
    val value: UUID
}

/** ユーザー識別子（users.user_uuid）。全 BC が参照する（§7 コンテキストマップ）。 */
@JvmInline
value class UserId(override val value: UUID) : EntityId {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): UserId = UserId(UUID.randomUUID())

        /** 不正な文字列で IllegalArgumentException を投げる。検証済みの内部用途向け。 */
        fun of(text: String): UserId = UserId(UUID.fromString(text))

        /** 外部入力向け。パースできなければ null を返す（呼び出し側で DomainError に変換 §10）。 */
        fun ofOrNull(text: String): UserId? =
            runCatching { UserId(UUID.fromString(text)) }.getOrNull()
    }
}
