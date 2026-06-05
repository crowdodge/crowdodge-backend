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

    // presentation 層の DTO は kotlinx.serialization（§1）。
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}
