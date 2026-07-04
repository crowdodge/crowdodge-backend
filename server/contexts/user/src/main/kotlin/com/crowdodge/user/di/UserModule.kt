package com.crowdodge.user.di

import com.crowdodge.user.application.command.AuthenticateWithGoogleUseCase
import com.crowdodge.user.application.command.LogoutUseCase
import com.crowdodge.user.application.command.RefreshSessionUseCase
import com.crowdodge.user.application.port.AppTokenPort
import com.crowdodge.user.application.port.GoogleCalendarCredentialStore
import com.crowdodge.user.application.port.GoogleCalendarListGateway
import com.crowdodge.user.application.port.GoogleCalendarProxyGateway
import com.crowdodge.user.application.port.GoogleOAuthGateway
import com.crowdodge.user.application.port.GoogleOAuthTokenRefreshGateway
import com.crowdodge.user.application.port.TokenCipher
import com.crowdodge.user.application.query.ProxyGoogleCalendarUseCase
import com.crowdodge.user.application.query.ResolveGoogleCalendarConnectionUseCase
import com.crowdodge.user.application.service.GoogleAccessTokenProvider
import com.crowdodge.user.application.service.UserCalendarSelectionService
import com.crowdodge.user.domain.repository.UserAuthRefreshTokenRepository
import com.crowdodge.user.domain.repository.UserCalendarRepository
import com.crowdodge.user.domain.repository.UserDeviceRepository
import com.crowdodge.user.domain.repository.UserGoogleCredentialRepository
import com.crowdodge.user.domain.repository.UserRepository
import com.crowdodge.user.domain.repository.UserSettingRepository
import com.crowdodge.user.infrastructure.db.ExposedGoogleCalendarCredentialStore
import com.crowdodge.user.infrastructure.db.ExposedUserAuthRefreshTokenRepository
import com.crowdodge.user.infrastructure.db.ExposedUserCalendarRepository
import com.crowdodge.user.infrastructure.db.ExposedUserDeviceRepository
import com.crowdodge.user.infrastructure.db.ExposedUserGoogleCredentialRepository
import com.crowdodge.user.infrastructure.db.ExposedUserRepository
import com.crowdodge.user.infrastructure.db.ExposedUserSettingRepository
import com.crowdodge.user.infrastructure.google.GoogleOAuthTokenGateway
import com.crowdodge.user.infrastructure.google.KtorGoogleCalendarListGateway
import com.crowdodge.user.infrastructure.google.KtorGoogleCalendarProxyGateway
import com.crowdodge.user.infrastructure.security.JwtAppTokenAdapter
import org.koin.dsl.module

fun userModule(googleCalendarApiBaseUrl: String) = module {
    single<UserRepository> { ExposedUserRepository() }
    single<UserCalendarRepository> { ExposedUserCalendarRepository() }
    single<UserDeviceRepository> { ExposedUserDeviceRepository() }
    single<UserSettingRepository> { ExposedUserSettingRepository() }
    single<UserGoogleCredentialRepository> { ExposedUserGoogleCredentialRepository(get<TokenCipher>()) }
    single<GoogleCalendarCredentialStore> { ExposedGoogleCalendarCredentialStore(get<TokenCipher>()) }
    single<UserAuthRefreshTokenRepository> { ExposedUserAuthRefreshTokenRepository() }
    single { GoogleOAuthTokenGateway(get(), get()) }
    single<GoogleOAuthGateway> { get<GoogleOAuthTokenGateway>() }
    single<GoogleOAuthTokenRefreshGateway> { get<GoogleOAuthTokenGateway>() }
    single<GoogleCalendarProxyGateway> {
        KtorGoogleCalendarProxyGateway(get(), googleCalendarApiBaseUrl)
    }
    single { GoogleAccessTokenProvider(get(), get(), get()) }
    single<GoogleCalendarListGateway> {
        KtorGoogleCalendarListGateway(get(), googleCalendarApiBaseUrl, get())
    }
    single { JwtAppTokenAdapter(get()) }
    single<AppTokenPort> { get<JwtAppTokenAdapter>() }
    single { AuthenticateWithGoogleUseCase(get(), get(), get(), get(), get(), get(), get()) }
    single { ResolveGoogleCalendarConnectionUseCase(get(), get(), get()) }
    single { UserCalendarSelectionService(get(), get(), get(), get(), get()) }
    single { ProxyGoogleCalendarUseCase(get(), get(), get(), get(), get()) }
    single { RefreshSessionUseCase(get(), get(), get()) }
    single { LogoutUseCase(get(), get(), get()) }
}
