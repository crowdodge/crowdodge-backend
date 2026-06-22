package com.crowdodge.shared.infra.db

/**
 * PostgreSQL の SQLSTATE。整合性違反の種別判定に使う（一意違反と FK/NOT NULL/CHECK を区別するため）。
 */
object PostgresSqlState {
    /** unique_violation（一意制約違反）。 */
    const val UNIQUE_VIOLATION: String = "23505"
}
