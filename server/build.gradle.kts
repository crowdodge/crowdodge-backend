import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import io.gitlab.arturbosch.detekt.report.ReportMergeTask

// ルートビルド。プラグインのバージョンをここで確定し、各モジュールが alias で適用する。
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "com.crowdodge"
    version = "0.1.0-SNAPSHOT"
}

// version catalog のアクセサはトップレベルでのみ解決できるため、subprojects へ渡す値をここで捕捉する。
val detektPluginId = libs.plugins.detekt.get().pluginId
val detektFormatting = libs.detekt.formatting

// 各モジュールが出す SARIF を 1 本に統合する（CI の reviewdog 連携の土台）。
val detektReportMerge by tasks.registering(ReportMergeTask::class) {
    output.set(layout.buildDirectory.file("reports/detekt/merged.sarif"))
}

// detekt（静的解析＋整形）を全モジュールへ適用。整形ルールは detekt-formatting（ktlint 内包）。
subprojects {
    apply(plugin = detektPluginId)

    configure<DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("config/detekt/detekt.yml"))
        // SARIF のパスを git ルート相対（server/...）で出力し、CI の reviewdog が PR 差分に対応付けられるようにする。
        basePath = rootProject.projectDir.parent
    }

    dependencies {
        "detektPlugins"(detektFormatting)
    }

    tasks.withType<Detekt>().configureEach {
        jvmTarget = "21"
        reports {
            // レポートは SARIF のみ（ローカル・CI 共通。reviewdog はこれを読む）。
            sarif.required.set(true)
            html.required.set(false)
            xml.required.set(false)
            md.required.set(false)
            txt.required.set(false)
        }
        finalizedBy(detektReportMerge)
    }

    detektReportMerge.configure {
        input.from(tasks.withType<Detekt>().map { it.sarifReportFile })
    }
}
