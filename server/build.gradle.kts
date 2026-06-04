// ルートビルド。プラグインのバージョンをここで確定し、各モジュールが alias で適用する。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

allprojects {
    group = "com.crowdodge"
    version = "0.1.0-SNAPSHOT"
}
