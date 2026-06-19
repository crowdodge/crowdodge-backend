package com.crowdodge.shared.kernel

import kotlin.uuid.Uuid

/**
 * UUID ベース識別子の基底。主キーは `<単数テーブル名>_uuid`（§12 命名規約）。
 * 各 BC は固有の識別子（EventId / DestinationId など）をこの形で定義する。
 */
interface EntityId {
    val value: Uuid
}

/** ユーザー識別子（users.user_uuid）。全 BC が参照する（§7 コンテキストマップ）。 */
@JvmInline
value class UserId(override val value: Uuid) : EntityId {
    companion object {
        fun new(): UserId = UserId(Uuid.random())
    }
}
