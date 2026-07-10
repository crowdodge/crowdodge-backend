plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // presentation/application/domain → shared:kernel（§4 の依存ルール）。
    // infrastructure 着手時に shared:infra / ktor / exposed を追加する。
    implementation(projects.shared.kernel)
    implementation(projects.shared.infra)
    implementation(libs.arrow.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.exposed.r2dbc)
    implementation(libs.firebase.admin)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)

    // presentation 層の DTO は kotlinx.serialization（§1）。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.arrow)
    testImplementation(libs.kotest.extensions.testcontainers)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
