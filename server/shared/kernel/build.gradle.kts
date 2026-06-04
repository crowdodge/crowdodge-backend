plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // domain は Kotlin 標準 + arrow-core + coroutines のみに依存（§4）。
    // Ktor / Exposed / Koin は import しない。
    api(libs.arrow.core)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
