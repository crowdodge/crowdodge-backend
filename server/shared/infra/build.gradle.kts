plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    api(projects.shared.kernel)

    // R2DBC + Exposed（永続化基盤 §12）
    api(libs.exposed.core)
    api(libs.exposed.r2dbc)
    // date/time 列の Exposed 型を使う。Instant は shared infra の timestamptz 列型で扱う。
    api(libs.exposed.kotlin.datetime)
    implementation(libs.r2dbc.pool)
    implementation(libs.r2dbc.spi)
    runtimeOnly(libs.r2dbc.postgresql)

    // readiness の SELECT 1 にタイムアウトを掛けるため明示依存（exposed-r2dbc 経由でも入るが明示する）
    implementation(libs.kotlinx.coroutines.core)

    // Problem(RFC9457) を Ktor 応答に載せるため最小の ktor-server-core を参照（§3 shared/infra）
    implementation(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.logback.classic)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    // readiness の DB ping を実 PostgreSQL で結合検証（§13 infrastructure）
    testImplementation(libs.kotest.extensions.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql) // PostgreSQLContainer の JDBC ベース起動待ち用
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
