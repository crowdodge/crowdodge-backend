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
    // app だけが全 BC（現時点では shared）を知り、Koin で配線する（§4）。
    implementation(project(":shared:kernel"))
    implementation(project(":shared:infra"))

    // Ktor サーバ
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)

    // DI
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

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
    testImplementation(libs.kotlin.test.junit5)
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
