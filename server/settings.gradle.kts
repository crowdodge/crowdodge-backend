rootProject.name = "crowdodge-backend"

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

// モジュラーモノリス（§2.1）。基盤（app + shared）のみ。
// contexts/<bc> は §14 ロードマップに沿って後続で追加する。
include(
    ":app",
    ":shared:kernel",
    ":shared:infra",
    ":konsist",
)
