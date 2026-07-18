package com.crowdodge.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import io.kotest.core.spec.style.FunSpec

/**
 * 依存方向の自動検査（§4）。
 * 層をパッケージでスコープして検査する。contexts/ BC 追加時に自動で適用される。
 * 検査自体は Konsist の assertFalse で行い、ランナーは Kotest（FunSpec）。
 */
class ArchitectureTest : FunSpec({

    val frameworks = listOf("io.ktor", "org.koin", "org.jetbrains.exposed", "io.r2dbc")

    test("shared kernel はフレームワークに依存しない") {
        Konsist.scopeFromProject()
            .files
            .withPackage("com.crowdodge.shared.kernel..")
            .assertFalse { file -> file.hasImport { imp -> frameworks.any { imp.name.startsWith(it) } } }
    }

    test("domain 層はフレームワークに依存しない") {
        Konsist.scopeFromProject()
            .files
            .withPackage("..domain..")
            .assertFalse { file -> file.hasImport { imp -> frameworks.any { imp.name.startsWith(it) } } }
    }

    test("application 層はフレームワークに依存しない") {
        Konsist.scopeFromProject()
            .files
            .withPackage("..application..")
            .assertFalse { file -> file.hasImport { imp -> frameworks.any { imp.name.startsWith(it) } } }
    }

    test("presentation は infrastructure に依存しない") {
        Konsist.scopeFromProject()
            .files
            .withPackage("..presentation..")
            .assertFalse { file -> file.hasImport { it.name.contains(".infrastructure.") } }
    }

    test("infrastructure は presentation に依存しない") {
        Konsist.scopeFromProject()
            .files
            .withPackage("..infrastructure..")
            .assertFalse { file -> file.hasImport { it.name.contains(".presentation.") } }
    }

    test("readmodel は役割が許可された公開port・Table定義・sharedと公開UUIDだけに依存する") {
        val allowedRolePrefixes = listOf(
            "com.crowdodge.shared.",
        )
        val allowedExactImports = setOf(
            "com.crowdodge.notification.domain.model.EventUuid",
            "com.crowdodge.congestion.domain.model.EventUuid",
            "com.crowdodge.congestion.domain.model.EventCongestionForecastUuid",
        )
        Konsist.scopeFromProduction()
            .files
            .withPackage("com.crowdodge.readmodel..")
            .assertFalse { file ->
                file.hasImport { imp ->
                    imp.name.startsWith("com.crowdodge.") &&
                        imp.name !in allowedExactImports &&
                        allowedRolePrefixes.none { imp.name.startsWith(it) } &&
                        !imp.name.contains(".application.port.") &&
                        !imp.name.contains(".infrastructure.persistence.")
                }
            }
    }

    test("readmodel は Exposed の書き込み関数を import しない") {
        val writeFunctions = listOf(
            "insert",
            "update",
            "deleteWhere",
            "upsert",
            "batchUpsert",
            "replace",
            "insertIgnore",
        )
        Konsist.scopeFromProduction()
            .files
            .withPackage("com.crowdodge.readmodel..")
            .assertFalse { file ->
                file.hasImport { imp -> writeFunctions.any { imp.name.endsWith(".$it") } }
            }
    }

    test("contexts は readmodel に依存しない") {
        val contextPackages = listOf(
            "com.crowdodge.user..",
            "com.crowdodge.event..",
            "com.crowdodge.notification..",
            "com.crowdodge.distination..",
            "com.crowdodge.congestion..",
        )
        contextPackages.forEach { contextPackage ->
            Konsist.scopeFromProject()
                .files
                .withPackage(contextPackage)
                .assertFalse { file -> file.hasImport { it.name.startsWith("com.crowdodge.readmodel.") } }
        }
    }
})
