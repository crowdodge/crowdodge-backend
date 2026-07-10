package com.crowdodge.user.application.command

import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.domain.model.FcmToken
import com.crowdodge.user.domain.model.UserDevice
import com.crowdodge.user.domain.model.UserDeviceUuid
import com.crowdodge.user.domain.repository.UserDeviceRepository
import io.kotest.assertions.arrow.core.shouldBeLeft
import io.kotest.assertions.arrow.core.shouldBeRight
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlin.uuid.Uuid

private object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T = block()
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class InMemoryDeviceRepository : UserDeviceRepository {
    val byToken = mutableMapOf<String, UserDevice>()
    var saveCount = 0

    override suspend fun save(userDevice: UserDevice) {
        saveCount += 1
        byToken[userDevice.fcmToken.value] = userDevice
    }

    override suspend fun delete(userUuid: UserUuid, userDeviceUuid: UserDeviceUuid) = Unit

    override suspend fun findByUserUuid(userUuid: UserUuid): List<UserDevice> =
        byToken.values.filter { it.userUuid == userUuid }

    override suspend fun findByFcmToken(fcmToken: FcmToken): UserDevice? = byToken[fcmToken.value]
}

class RegisterUserDeviceUseCaseTest : FunSpec({

    test("新規トークンは登録される") {
        val repo = InMemoryDeviceRepository()
        val userUuid = UserUuid(Uuid.random())

        RegisterUserDeviceUseCase(repo, ImmediateTransactionRunner)
            .handle(RegisterUserDeviceCommand(userUuid, "token-1"))
            .shouldBeRight()

        repo.byToken["token-1"]?.userUuid shouldBe userUuid
        repo.saveCount shouldBe 1
    }

    test("既存トークンは現ユーザーに付け替え、既存の deviceUuid を維持する") {
        val repo = InMemoryDeviceRepository()
        val firstUser = UserUuid(Uuid.random())
        val secondUser = UserUuid(Uuid.random())
        val useCase = RegisterUserDeviceUseCase(repo, ImmediateTransactionRunner)

        useCase.handle(RegisterUserDeviceCommand(firstUser, "token-1")).shouldBeRight()
        val firstDeviceUuid = checkNotNull(repo.byToken["token-1"]).userDeviceUuid
        useCase.handle(RegisterUserDeviceCommand(secondUser, "token-1")).shouldBeRight()

        repo.byToken["token-1"]?.userDeviceUuid shouldBe firstDeviceUuid
        repo.byToken["token-1"]?.userUuid shouldBe secondUser
        repo.saveCount shouldBe 2
    }

    test("既存トークンが同じユーザーに紐づいている場合は保存しない") {
        val repo = InMemoryDeviceRepository()
        val userUuid = UserUuid(Uuid.random())
        val useCase = RegisterUserDeviceUseCase(repo, ImmediateTransactionRunner)

        useCase.handle(RegisterUserDeviceCommand(userUuid, "token-1")).shouldBeRight()
        val firstDeviceUuid = checkNotNull(repo.byToken["token-1"]).userDeviceUuid
        useCase.handle(RegisterUserDeviceCommand(userUuid, "token-1")).shouldBeRight()

        repo.byToken["token-1"]?.userDeviceUuid shouldBe firstDeviceUuid
        repo.byToken["token-1"]?.userUuid shouldBe userUuid
        repo.saveCount shouldBe 1
    }

    test("空トークンは ValidationError") {
        RegisterUserDeviceUseCase(InMemoryDeviceRepository(), ImmediateTransactionRunner)
            .handle(RegisterUserDeviceCommand(UserUuid(Uuid.random()), "   "))
            .shouldBeLeft()
    }
})
