package com.crowdodge.user.domain.repository

import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.model.GoogleId
import com.crowdodge.user.domain.model.User

/**
 * User 集約（identity + 設定）の永続化ポート。実装は infrastructure、トランザクション境界は application（§11）。
 */
interface UserRepository {
    /** 集約単位で保存する（新規・更新）。 */
    suspend fun save(user: User)

    suspend fun findById(userId: UserId): User?

    /** Google サインインの突合に使う（§13 認証）。 */
    suspend fun findByGoogleId(googleId: GoogleId): User?
}
