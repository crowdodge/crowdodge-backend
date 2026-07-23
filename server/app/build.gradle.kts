import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

kotlin {
    jvmToolchain(21)
}

ktor {
    fatJar {
        archiveFileName.set("app.jar")
    }
}

application {
    // HOCON(application.conf) の ktor.application.modules を EngineMain が読み込む。
    mainClass.set("io.ktor.server.netty.EngineMain")
}

dependencies {
    // app だけが全 BC（現時点では shared）を知り、Koin で配線する（§4）。
    implementation(projects.shared.kernel)
    implementation(projects.shared.infra)
    implementation(projects.contexts.user)
    implementation(projects.contexts.event)
    implementation(projects.contexts.destination)
    implementation(projects.contexts.congestion)
    implementation(projects.contexts.notification)

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

// ビルド時の設定
tasks.named<JavaExec>("run") {
  jvmArgs("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=0.0.0.0:5005")
}
tasks.named<Delete>("clean") {
  setDelete(fileTree(layout.buildDirectory))
}
tasks.named<ShadowJar>("shadowJar") {
    // NOTE: [以降例](https://gradleup.com/shadow/changes/#migration-example)に従って、マージすべきか・先勝ちで良いかを判断する

    // デフォルト設定
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    mergeServiceFiles()

    // 以下に例外設定を追加

    // ServiceLoaderのKtorの設定ローダの重複をマージ
    filesMatching("META-INF/services/**") {
        // 想定は`io.ktor.server.config.ConfigLoader`
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
}