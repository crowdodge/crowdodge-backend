package com.crowdodge.app.migration

import com.crowdodge.distination.infrastructure.persistence.EventDestinationLinksTable
import com.crowdodge.distination.infrastructure.persistence.EventDestinationsTable
import com.crowdodge.event.infrastructure.persistence.EventCalendarSyncsTable
import com.crowdodge.event.infrastructure.persistence.EventsTable
import com.crowdodge.user.infrastructure.persistence.UserCalendarsTable
import com.crowdodge.user.infrastructure.persistence.UserDevicesTable
import com.crowdodge.user.infrastructure.persistence.UserSettingsTable
import com.crowdodge.user.infrastructure.persistence.UserSubscriptionsTable
import com.crowdodge.user.infrastructure.persistence.UsersTable
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * マイグレーション SQL 生成ツール。
 *
 * Exposed の Table 定義と「現在の DB スキーマ」を比較し、差分があれば
 * `db/migration/V<次の番号>__change.sql` を出力する。生成のため DB 接続が必要（JDBC）。
 *   DB_PORT=... ./gradlew :app:generateMigration
 *
 * 各 BC で Table を定義したら [tables] に追加する。バージョン番号は既存ファイルから
 * 自動採番し、差分が無ければ生成しない。生成 SQL は適用前にレビューして整える
 * （例: SERIAL と CREATE SEQUENCE の重複）。
 */
private val tables: Array<Table> = arrayOf(
    // 例: UsersTable, EventTable, ...（各 BC の infrastructure/persistence で定義）
    UsersTable,
    UserSettingsTable,
    UserCalendarsTable,
    UserSubscriptionsTable,
    UserDevicesTable,
    EventsTable,
    EventCalendarSyncsTable,
    EventDestinationsTable,
    EventDestinationLinksTable,
)

private const val MIGRATION_DIR = "src/main/resources/db/migration"

// tables は静的な小配列で、Exposed のマイグレーション API が vararg のみを受けるため
// spread は不可避。コピーのコストも無視できるため SpreadOperator を抑制する。
@Suppress("SpreadOperator")
@OptIn(org.jetbrains.exposed.v1.core.ExperimentalDatabaseMigrationApi::class)
fun main() {
    val log = LoggerFactory.getLogger("GenerateMigration")
    if (tables.isEmpty()) {
        log.warn("対象 Table が未登録です。GenerateMigrationMain の tables に Exposed Table を追加してください。")
        return
    }
    val config = loadDatabaseConfig()

    val pending = FlywayMigrator.pending(config).isNotEmpty()
    if (pending) {
        println("未適用のマイグレーションがります。")
        println("重複生成を防ぐため、先にDBへ適用(migrate)してください。")
        return
    }

    val db = Database.connect(
        url = jdbcUrl(config),
        driver = "org.postgresql.Driver",
        user = config.username,
        password = config.password,
    )
    transaction(db) {
        if (MigrationUtils.statementsRequiredForDatabaseMigration(*tables).isEmpty()) {
            log.info("スキーマ差分なし。マイグレーションは生成しません。")
            return@transaction
        }
        // 既存 V<番号>__*.sql の最大番号 +1 を採番する。
        val maxVersion = File(MIGRATION_DIR).listFiles().orEmpty()
            .mapNotNull { Regex("""^V(\d+)__""").find(it.name)?.groupValues?.get(1)?.toInt() }
            .maxOrNull() ?: 0
        val next = maxVersion + 1
        val files = MigrationUtils.generateMigrationScript(
            *tables,
            scriptDirectory = MIGRATION_DIR,
            scriptName = "V${next}__change",
        )
        log.info("生成しました: {}", files)
    }
}
