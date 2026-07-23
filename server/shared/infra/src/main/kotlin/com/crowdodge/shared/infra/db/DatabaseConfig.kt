package com.crowdodge.shared.infra.db

/**
 * DB 接続設定（§12）。認証情報は URL に埋め込まず分離して保持する
 * （ログ漏洩・パスワード中の特殊文字による URL パース破綻を避けるため）。
 *
 * 実行時のノンブロッキング接続（R2DBC + r2dbc-pool）で使う共通の接続情報。
 * host/port/database + username/password を ConnectionFactoryOptions に分離して渡す（[R2dbcFactory]）。
 * JDBC URL（マイグレーション用）は migration 専用のため app 側で組み立てる。
 *
 * Ktor の設定読み込みは app 層が担い、ここはプレーンな値オブジェクトに留める。
 */
data class DatabaseConfig(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val sslMode: DatabaseSslMode = DatabaseSslMode.DISABLE,
    val pgbouncer: Boolean = false,
)

enum class DatabaseSslMode(val configValue: String) {
    DISABLE("disable"),
    ALLOW("allow"),
    PREFER("prefer"),
    REQUIRE("require"),
    VERIFY_CA("verify-ca"),
    VERIFY_FULL("verify-full"),
    ;

    companion object {
        fun fromConfig(value: String): DatabaseSslMode =
            entries.firstOrNull { it.configValue == value.lowercase() }
                ?: error(
                    "Unsupported DB_SSL_MODE '$value'. " +
                        "Use one of: ${entries.joinToString { it.configValue }}",
                )
    }
}
