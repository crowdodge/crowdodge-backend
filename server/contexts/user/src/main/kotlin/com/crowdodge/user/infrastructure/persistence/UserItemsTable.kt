package com.crowdodge.user.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable

object UserItemsTable : TimestampedTable("user_items") {
    val userItemUuid = uuid("user_item_id_uuid")
    val userUuid = reference("user_uuid", UsersTable.userUuid)
    val itemType = text("item_type")
    val itemCount = integer("item_count")
    init {
        uniqueIndex(userUuid, itemType)
    }
}