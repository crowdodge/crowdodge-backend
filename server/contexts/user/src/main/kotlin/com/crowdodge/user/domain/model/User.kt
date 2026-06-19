package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.Email.Companion.email
import com.crowdodge.user.domain.model.GoogleId.Companion.googleId

/**
 * メールアドレス VO（users.email）。空文字を許さない。
 * 生成は [email] スマートコンストラクタのみ（private constructor で施錠＝検証迂回不可）。
 * 失敗は [UserError.ValidationError] として `raise`。利用側は `import ...Email.Companion.email` して `either {}` 内で `email(x)`。
 */
@JvmInline
value class Email private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.email(value: String): Email {
            ensure(value.isNotBlank()) { UserError.ValidationError.BlankEmail }
            return Email(value)
        }
    }
}

/**
 * Google アカウント識別子 VO（users.google_id）。空文字を許さない。
 * 生成は [googleId] のみ。
 */
@JvmInline
value class GoogleId private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.googleId(value: String): GoogleId {
            ensure(value.isNotBlank()) { UserError.ValidationError.BlankGoogleId }
            return GoogleId(value)
        }
    }
}

/**
 * ユーザー集約ルート（users）。identity（[googleId]/[email]）と [UserSettings]（1:1）を内包する。
 * カレンダー選択・デバイスは別集約（[UserCalendar]/[UserDevice]）で、[UserId] を値参照する。
 */
class User private constructor(
    val userId: UserId,
    val googleId: GoogleId,
    val email: Email,
) {
    companion object {
        /** 新規登録（新しい [UserId] を採番）。 */
        fun register(googleId: GoogleId, email: Email): User =
            User(UserId.new(), googleId, email)

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        fun reconstitute(
            userId: UserId,
            googleId: GoogleId,
            email: Email,
        ): User = User(userId, googleId, email)
    }
}
