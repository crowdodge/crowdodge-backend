rootProject.name = "crowdodge-backend"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

// モジュラーモノリス（§2.1）。基盤（app + shared）+ contexts/<bc>。
// contexts/<bc> は §14 ロードマップに沿って追加していく。
include(
    ":app",
    ":shared:kernel",
    ":shared:infra",
    ":konsist",
    ":contexts:user",
    ":contexts:event",
    ":contexts:destination",
)
