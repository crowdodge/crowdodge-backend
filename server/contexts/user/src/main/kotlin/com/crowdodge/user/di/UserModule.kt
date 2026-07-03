package com.crowdodge.user.di

import com.crowdodge.user.application.command.AuthenticateWithGoogleUseCase
import com.crowdodge.user.application.command.LogoutUseCase
import com.crowdodge.user.application.command.RefreshSessionUseCase
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.application.port.GoogleOAuthGateway
import com.crowdodge.user.application.port.TokenCipher
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.domain.repository.UserDeviceRepository
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import com.crowdodge.user.domain.repository.UserRepository
import com.crowdodge.user.domain.repository.UserSettingRepository
import com.crowdodge.user.infrastructure.db.ExposedUserAuthRefreshTokenRepository
import com.crowdodge.user.infrastructure.db.ExposedUserCalendarRepository
import com.crowdodge.user.infrastructure.db.ExposedUserDeviceRepository
import com.crowdodge.user.infrastructure.db.ExposedUserGoogleCredentialRepository
import com.crowdodge.user.infrastructure.db.ExposedUserRepository
import com.crowdodge.user.infrastructure.db.ExposedUserSettingRepository
import com.crowdodge.user.infrastructure.google.GoogleOAuthTokenGateway
import com.crowdodge.user.infrastructure.security.JwtAppTokenAdapter
import org.koin.dsl.module

fun userModule() = module {
    single<UserRepository> { ExposedUserRepository() }
    single<UserCalendarRepository> { ExposedUserCalendarRepository() }
    single<UserDeviceRepository> { ExposedUserDeviceRepository() }
    single<UserSettingRepository> { ExposedUserSettingRepository() }
    single<UserGoogleCredentialRepository> { ExposedUserGoogleCredentialRepository(get<TokenCipher>()) }
    single<UserAuthRefreshTokenRepository> { ExposedUserAuthRefreshTokenRepository() }
    single { GoogleOAuthTokenGateway(get(), get()) }
    single<GoogleOAuthGateway> { get<GoogleOAuthTokenGateway>() }
    single { JwtAppTokenAdapter(get()) }
    single<AppTokenPort> { get<JwtAppTokenAdapter>() }
    single { AuthenticateWithGoogleUseCase(get(), get(), get(), get(), get(), get(), get()) }
    single { RefreshSessionUseCase(get(), get(), get()) }
    single { LogoutUseCase(get(), get(), get()) }
}
