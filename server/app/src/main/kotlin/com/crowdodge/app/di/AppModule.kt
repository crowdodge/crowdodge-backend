package com.crowdodge.app.di

import com.crowdodge.app.db.databaseConfig
import com.crowdodge.shared.infra.db.DatabaseConfig
import com.crowdodge.shared.infra.db.DatabaseReadinessProbe
import com.crowdodge.shared.infra.db.ExposedTransactionRunner
import com.crowdodge.shared.infra.db.R2dbcConnection
import com.crowdodge.shared.infra.db.R2dbcFactory
import com.crowdodge.shared.kernel.ReadinessProbe
import com.crowdodge.shared.kernel.TransactionRunner
import io.ktor.server.application.ApplicationEnvironment
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.koin.dsl.module
import org.koin.dsl.onClose

/**
 * アプリ全体の Koin モジュール（§4）。
 * 各 BC 着手時に `contexts/<bc>/di/<Bc>Module.kt` を追加し、ここで束ねる。
 */
fun appModule(environment: ApplicationEnvironment) = module {
    single<DatabaseConfig> { environment.databaseConfig() }
    // Koin 停止（ApplicationStopping）時に onClose でプールを破棄する。
    single<R2dbcConnection> { R2dbcFactory.connect(get()) } onClose { it?.close() }
    single<R2dbcDatabase> { get<R2dbcConnection>().database }
    single<TransactionRunner> { ExposedTransactionRunner(get()) }
    // readiness（/ready）用 DB 到達性プローブ。R2DBC は遅延接続のためここで初接続する。
    single<ReadinessProbe> { DatabaseReadinessProbe(get()) }
    // ドメインイベントの配送実装（DomainEventPublisher）は未確定のため未配線（§9）。
}
