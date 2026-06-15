package com.crowdodge.user.infrastructure.persistence

import com.crowdodge.shared.infra.db.TimestampedTable
import org.jetbrains.exposed.v1.datetime.timestamp

object UserSubscriptionsTable : TimestampedTable("user_subscriptions") {
    val userUuid = reference("user_uuid", UsersTable.userUuid)
    val planName = text("plan_name")
    val status = text("status")
    val expiresAt = timestamp("expires_at")
    val rcOriginalTransactionId = text("rc_original_transaction_id")
    override val primaryKey: PrimaryKey
        get() = PrimaryKey(userUuid)
}
