package com.crowdodge.user.domain.repository

import arrow.core.Either
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.GoogleId
import com.crowdodge.user.domain.model.User

/**
 * User 集約（identity + 設定）の永続化ポート。実装は infrastructure、トランザクション境界は application（§11）。
 */
interface UserRepository {
    /**
     * 新規登録（insert）。email の一意制約違反は [UserError.ConflictError.DuplicateEmail] を返す
     * （事前チェックの有無に関わらず DB 制約が番人。並行時も安全）。
     */
    suspend fun create(user: User): Either<UserError.ConflictError.DuplicateEmail, Unit>

    /**
     * 既存ユーザーの更新（userUuid 一致行を update）。他ユーザーと同じ email への変更は
     * [UserError.ConflictError.DuplicateEmail] を返す。
     * 前提: 認証済みの userUuid は存在する想定のため対象不在は扱わない（不在時は 0 行＝no-op）。
     */
    suspend fun update(user: User): Either<UserError.ConflictError.DuplicateEmail, Unit>

    suspend fun findByUserUuid(userUuid: UserUuid): User?

    /** Google サインインの突合に使う（§13 認証）。 */
    suspend fun findByGoogleId(googleId: GoogleId): User?
}
