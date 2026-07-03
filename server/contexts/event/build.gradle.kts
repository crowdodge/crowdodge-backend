plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(projects.shared.kernel)

    implementation(projects.shared.infra)
    implementation(libs.exposed.core)
    implementation(libs.exposed.kotlin.datetime)

    implementation(libs.ktor.client.core)
    implementation(libs.koin.ktor)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.arrow)
    testImplementation(libs.kotest.extensions.testcontainers)
    testImplementation(libs.ktor.client.mock)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
