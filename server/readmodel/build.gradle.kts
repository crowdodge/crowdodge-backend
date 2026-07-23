plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // BC 横断の読み取り専用クエリ。contexts の Table 定義を import して SELECT のみ行う。
    implementation(projects.shared.kernel)
    implementation(projects.shared.infra)
    // notification の公開ポートを実装する。
    implementation(projects.contexts.notification)
    // 他 BC の Table 定義を import するための依存（読み取り専用）。
    implementation(projects.contexts.event)
    implementation(projects.contexts.user)
    implementation(projects.contexts.destination)
    implementation(projects.contexts.congestion)
    implementation(libs.exposed.core)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.r2dbc)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.extensions.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
