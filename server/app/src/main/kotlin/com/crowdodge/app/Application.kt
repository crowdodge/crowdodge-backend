package com.crowdodge.app

import com.crowdodge.app.plugins.configureKoin
import com.crowdodge.app.plugins.configureMonitoring
import com.crowdodge.app.plugins.configureRouting
import com.crowdodge.app.plugins.configureSerialization
import com.crowdodge.app.plugins.configureStatusPages
import io.ktor.server.application.Application

/**
 * Ktor アプリのエントリポイント（§3 app）。
 * 実行は `io.ktor.server.netty.EngineMain` が application.conf を読んでこの module を起動する。
 *
 * マイグレーションはアプリ起動では実行しない。専用コマンド（`:app:flywayMigrate` /
 * `:app:generateMigration`）でアプリとは独立したライフサイクルで実行する。
 */
fun Application.module() {
    configureKoin()
    // StatusPages は下流プラグイン/ハンドラの例外を広く捕捉できるよう早期に install する。
    configureStatusPages()
    configureMonitoring()
    configureSerialization()
    configureRouting()
}
