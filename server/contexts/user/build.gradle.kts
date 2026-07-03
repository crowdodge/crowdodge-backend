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
    // domain のエラーハンドリング（内部=Raise / 境界=Either）。§4 で domain は arrow-core 依存可。
    implementation(libs.arrow.core)
    implementation(libs.exposed.core)
    implementation(libs.exposed.kotlin.datetime)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.koin.ktor)
    implementation(libs.java.jwt)

    // presentation 層の DTO は kotlinx.serialization（§1）。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.arrow)
    testImplementation(libs.kotest.property)
    testImplementation(libs.ktor.client.mock)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
