package com.crowdodge.user.domain.model

import arrow.core.raise.Raise
import arrow.core.raise.ensure
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.error.UserError
import com.crowdodge.user.domain.model.Email.Companion.email
import com.crowdodge.user.domain.model.GoogleId.Companion.googleId

/**
 * メールアドレス VO（users.email）。前後空白は trim し、基本形式（`@` を1つ含み local/domain が非空）を要求する。
 * 生成は [email] スマートコンストラクタのみ（private constructor で施錠＝検証迂回不可）。
 * 失敗は [UserError.ValidationError] として `raise`。利用側は `import ...Email.Companion.email` して `either {}` 内で `email(x)`。
 */
@JvmInline
value class Email private constructor(val value: String) {
    companion object {
        // 文字列の前に $$ をつけることで、ドルマーク2つ（$$）の時だけ変数展開するルールになります
        // これにより、1つの $ はそのまま文字として扱えるようになります
        @Suppress("MaximumLineLength", "MaxLineLength")
        val regex = Regex(
            """^[a-zA-Z0-9.!#$%&'*+\/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$"""
        )

        fun Raise<UserError.ValidationError>.email(value: String): Email {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { UserError.ValidationError.BlankEmail }
            ensure(isBasicEmailFormat(trimmed)) { UserError.ValidationError.InvalidEmail }
            return Email(trimmed)
        }

        /** HTML標準のEmailの形式と一致するかを検証 */
        private fun isBasicEmailFormat(value: String): Boolean =
            value.matches(regex)
    }
}

/**
 * Google アカウント識別子 VO（users.google_id）。外部発行の不透明トークン。前後空白は trim し、空は許さない。
 * 生成は [googleId] のみ。
 */
@JvmInline
value class GoogleId private constructor(val value: String) {
    companion object {
        fun Raise<UserError.ValidationError>.googleId(value: String): GoogleId {
            val trimmed = value.trim()
            ensure(trimmed.isNotBlank()) { UserError.ValidationError.BlankGoogleId }
            return GoogleId(trimmed)
        }
    }
}

/**
 * ユーザー集約ルート（users）。identity（[googleId]/[email]）と [UserSettings]（1:1）を内包する。
 * カレンダー選択・デバイスは別集約（[UserCalendar]/[UserDevice]）で、[UserUuid] を値参照する。
 */
class User private constructor(
    val userUuid: UserUuid,
    val googleId: GoogleId,
    val email: Email,
) {
    companion object {
        /** 新規登録（新しい [UserUuid] を採番）。 */
        fun register(googleId: GoogleId, email: Email): User =
            User(UserUuid.new(), googleId, email)

        /** 永続化済みの状態から再構築する（リポジトリ用）。 */
        fun reconstitute(
            userUuid: UserUuid,
            googleId: GoogleId,
            email: Email,
        ): User = User(userUuid, googleId, email)
    }
}
