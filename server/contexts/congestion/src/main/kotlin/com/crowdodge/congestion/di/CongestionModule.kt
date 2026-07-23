package com.crowdodge.congestion.di

import com.crowdodge.congestion.application.port.GenerationInputHashCalculator
import com.crowdodge.congestion.application.service.GenerateCongestionInfoUseCase
import com.crowdodge.congestion.domain.repository.EventCongestionForecastRepository
import com.crowdodge.congestion.infrastructure.db.ExposedEventCongestionForecastRepository
import com.crowdodge.congestion.infrastructure.hash.Sha256GenerationInputHashCalculator
import org.koin.dsl.module

/** 混雑 BC の依存関係を構成する Koin モジュールを返す。 */
fun congestionModule(maxConcurrency: Int) = module {
    single<EventCongestionForecastRepository> { ExposedEventCongestionForecastRepository() }
    single<GenerationInputHashCalculator> { Sha256GenerationInputHashCalculator() }
    single<GenerateCongestionInfoUseCase> {
        GenerateCongestionInfoUseCase(
            readModel = get(),
            generator = get(),
            forecasts = get(),
            transactions = get(),
            clock = get(),
            maxConcurrency = maxConcurrency,
            hashCalculator = get(),
        )
    }
}
