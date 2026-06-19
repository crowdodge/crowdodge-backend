package com.crowdodge.user.domain.repository

import com.crowdodge.shared.kernel.UserId
import com.crowdodge.user.domain.model.UserSetting

interface UserSettingRepository {
    suspend fun save(userSetting: UserSetting)

    suspend fun findById(userId: UserId): UserSetting?
}
