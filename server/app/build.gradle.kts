plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(21)
}

application {
    // HOCON(application.conf) の ktor.application.modules を EngineMain が読み込む。
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    // app だけが全 BC を知り、各 BC の DI モジュールと BC 間連携を束ねる。
    implementation(projects.shared.kernel)
    implementation(projects.shared.infra)
    implementation(projects.contexts.user)
    implementation(projects.contexts.event)
    implementation(projects.contexts.destination)
    implementation(projects.contexts.congestion)
    implementation(projects.contexts.notification)
    implementation(projects.readmodel)

    // Ktor サーバ
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.client.cio)
    implementation(libs.java.jwt)
    implementation(libs.firebase.admin)

    // DI
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    implementation(libs.kotlinx.coroutines.core)

    // マイグレーション SQL 生成ツール（開発時のみ。JDBC で現スキーマと差分比較）
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.migration.core)
    implementation(libs.exposed.migration.jdbc)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)

    // マイグレーション専用エントリポイント(MigrateMain)が application.conf を直接読む
    implementation(libs.typesafe.config)

    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// DB スキーマ管理ツール（アプリ起動とは独立したライフサイクルで実行する）。
tasks.register<JavaExec>("flywayMigrate") {
    group = "application"
    description = "Flyway マイグレーションを適用する（アプリ起動とは別プロセス）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.crowdodge.app.migration.MigrateMainKt"
}

tasks.register<JavaExec>("generateMigration") {
    group = "application"
    description = "Exposed の Table 定義から差分マイグレーション SQL を生成する（枠）"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.crowdodge.app.migration.GenerateMigrationMainKt"
}

tasks.register<JavaExec>("renewGoogleCalendarWatches") {
    group = "application"
    description = "Google Calendar watchの整合・更新を1回実行する"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.crowdodge.app.calendar.GoogleCalendarWatchRenewalMainKt"
}

tasks.register<JavaExec>("dispatchNotifications") {
    group = "application"
    description = "期限到来した通知スケジュールの FCM 送信を1回実行する"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "com.crowdodge.app.notification.NotificationDispatchMainKt"
}
