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
    implementation(libs.r2dbc.pool)
    implementation(libs.r2dbc.spi)
    runtimeOnly(libs.r2dbc.postgresql)

    // Problem(RFC9457) を Ktor 応答に載せるため最小の ktor-server-core を参照（§3 shared/infra）
    implementation(libs.ktor.server.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.logback.classic)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
