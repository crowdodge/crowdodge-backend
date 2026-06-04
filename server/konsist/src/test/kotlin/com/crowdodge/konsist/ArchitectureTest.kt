package com.crowdodge.konsist

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.ext.list.withPackage
import com.lemonappdev.konsist.api.verify.assertFalse
import kotlin.test.Test

/**
 * 依存方向の自動検査（§4）。
 * 層をパッケージでスコープして検査する。contexts/ BC 追加時に自動で適用される。
 */
class ArchitectureTest {

    private val frameworks = listOf("io.ktor", "org.koin", "org.jetbrains.exposed", "io.r2dbc")

    @Test
    fun `shared kernel はフレームワークに依存しない`() {
        Konsist.scopeFromProject()
            .files
            .withPackage("com.crowdodge.shared.kernel..")
            .assertFalse { file -> file.hasImport { imp -> frameworks.any { imp.name.startsWith(it) } } }
    }

    @Test
    fun `domain 層はフレームワークに依存しない`() {
        Konsist.scopeFromProject()
            .files
            .withPackage("..domain..")
            .assertFalse { file -> file.hasImport { imp -> frameworks.any { imp.name.startsWith(it) } } }
    }

    @Test
    fun `application 層はフレームワークに依存しない`() {
        Konsist.scopeFromProject()
            .files
            .withPackage("..application..")
            .assertFalse { file -> file.hasImport { imp -> frameworks.any { imp.name.startsWith(it) } } }
    }

    @Test
    fun `presentation は infrastructure に依存しない`() {
        Konsist.scopeFromProject()
            .files
            .withPackage("..presentation..")
            .assertFalse { file -> file.hasImport { it.name.contains(".infrastructure.") } }
    }

    @Test
    fun `infrastructure は presentation に依存しない`() {
        Konsist.scopeFromProject()
            .files
            .withPackage("..infrastructure..")
            .assertFalse { file -> file.hasImport { it.name.contains(".presentation.") } }
    }
}
