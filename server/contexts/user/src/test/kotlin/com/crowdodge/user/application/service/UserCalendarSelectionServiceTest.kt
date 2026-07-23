package com.crowdodge.user.application.service

import arrow.core.left
import arrow.core.right
import com.crowdodge.shared.kernel.DomainEvent
import com.crowdodge.shared.kernel.DomainEventPublisher
import com.crowdodge.shared.kernel.TransactionRunner
import com.crowdodge.shared.kernel.UserUuid
import com.crowdodge.user.application.port.GoogleCalendarAccessRole
import com.crowdodge.user.application.port.GoogleCalendarListGateway
import com.crowdodge.user.application.port.GoogleCalendarListItem
import com.crowdodge.user.domain.event.CalendarInitialSyncRequested
import com.crowdodge.user.domain.model.GoogleCalendarId.Companion.googleCalendarId
import com.crowdodge.user.domain.model.UserCalendar
import com.crowdodge.user.domain.repository.UserCalendarRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.Uuid

class UserCalendarSelectionServiceTest : FunSpec({
    val userUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000001"))
    val now = Instant.parse("2026-07-02T00:00:00Z")

    test("0件1件3件を許可し4件と重複とreaderを拒否する") {
        val service = fixture(userUuid, now).service

        service.planReplacement(userUuid, emptyList()).isRight() shouldBe true
        service.planReplacement(userUuid, listOf("one")).isRight() shouldBe true
        service.planReplacement(userUuid, listOf("one", "two", "three")).isRight() shouldBe true
        service.planReplacement(userUuid, listOf("one", "two", "three", "four")).leftOrNull() shouldBe
            com.crowdodge.user.domain.error.UserError.ValidationError.TooManyCalendarSelections
        service.planReplacement(userUuid, listOf("one", "one")).leftOrNull() shouldBe
            com.crowdodge.user.domain.error.UserError.ValidationError.DuplicateCalendarSelectionInput
        service.planReplacement(userUuid, listOf("reader")).leftOrNull() shouldBe
            com.crowdodge.user.domain.error.UserError.AuthorizationError.InsufficientCalendarAccess
    }

    test("追加維持解除差分を計算し維持対象のUUIDを保つがplanでは書き込まない") {
        val fixture = fixture(userUuid, now, selectedIds = listOf("one", "old"))

        val plan = fixture.service.planReplacement(userUuid, listOf("one", "two")).getOrNull()!!

        plan.additions.map { it.googleCalendarId } shouldContainExactly listOf("two")
        plan.retained.map { it.googleCalendarId } shouldContainExactly listOf("one")
        plan.removals.map { it.googleCalendarId } shouldContainExactly listOf("old")
        plan.additions.map { it.accessToken } shouldContainExactly listOf("access")
        plan.removals.map { it.accessToken } shouldContainExactly listOf("access")
        plan.retained.single().userCalendarUuid shouldBe fixture.repository.items.first().userCalendarUuid
        fixture.transactions.writeCount shouldBe 0
        fixture.repository.replaceCount shouldBe 0
    }

    test("commitだけがtransaction内で一括置換し追加分の初回同期eventをpublishする") {
        val fixture = fixture(userUuid, now, selectedIds = listOf("one", "old"))
        val plan = fixture.service.planReplacement(userUuid, listOf("one", "two")).getOrNull()!!

        fixture.service.commitReplacement(plan).isRight() shouldBe true

        fixture.transactions.writeCount shouldBe 1
        fixture.repository.replaceCount shouldBe 1
        fixture.repository.items.map { it.googleCalendarId.value } shouldContainExactly listOf("one", "two")
        fixture.publisher.events.map { it.userCalendarUuid } shouldContainExactly
            plan.additions.map { it.userCalendarUuid }
        fixture.publisher.publishedInsideTransaction shouldBe true
        fixture.operations shouldContainExactly listOf("replace", "publish")
    }

    test("一括置換が失敗したらcommitも失敗して初回同期eventをpublishしない") {
        val transactions = RecordingTransactions()
        val publisher = RecordingPublisher(transactions)
        val repository = object : UserCalendarRepository {
            override suspend fun create(userCalendar: UserCalendar) =
                com.crowdodge.user.domain.error.UserError.ConflictError.DuplicateCalendar.left()

            override suspend fun delete(
                userUuid: UserUuid,
                userCalendarUuid: com.crowdodge.user.domain.model.UserCalendarUuid,
            ) = Unit

            override suspend fun findByUserUuid(userUuid: UserUuid) = emptyList<UserCalendar>()
        }
        val service = UserCalendarSelectionService(
            calendarList = GoogleCalendarListGateway { error("呼ばれない") },
            accessTokens = tokenProvider { error("呼ばれない") },
            calendars = repository,
            transactions = transactions,
            publisher = publisher,
            clock = FixedSelectionClock(now),
        )
        val plan = CalendarSelectionPlan(
            userUuid = userUuid,
            additions = listOf(
                SelectedCalendarConnection(
                    userCalendarUuid = com.crowdodge.user.domain.model.UserCalendarUuid.new(),
                    userUuid = userUuid,
                    googleCalendarId = "one",
                    accessToken = "secret",
                ),
            ),
            retained = emptyList(),
            removals = emptyList(),
        )

        service.commitReplacement(plan).leftOrNull() shouldBe
            com.crowdodge.user.domain.error.UserError.ConflictError.DuplicateCalendar
        transactions.failedWriteCount shouldBe 1
        publisher.events shouldBe emptyList()
    }

    test("候補一覧へ選択状態を付与する") {
        val fixture = fixture(userUuid, now, selectedIds = listOf("one"))

        val available = fixture.service.listAvailable(userUuid).getOrNull()!!

        available.first { it.id == "one" }.selected shouldBe true
        available.first { it.id == "two" }.selected shouldBe false
    }

    test("選択解除と選択済み一覧取得をUserUuidでスコープする") {
        val fixture = fixture(userUuid, now, selectedIds = listOf("one", "two"))
        val removed = fixture.repository.items.first()

        fixture.service.removeSelection(userUuid, removed.userCalendarUuid)

        fixture.transactions.writeCount shouldBe 1
        fixture.service.listSelected(userUuid).map { it.googleCalendarId } shouldContainExactly listOf("two")
    }

    test("全選択をeligibleとinaccessibleへ分類して一時tokenを付与する") {
        val fixture = fixture(userUuid, now, selectedIds = listOf("one", "old"))

        val snapshot = fixture.service.inspectAllSelected().getOrNull()!!

        snapshot.eligible.map { it.googleCalendarId } shouldContainExactly listOf("one")
        snapshot.inaccessible.map { it.googleCalendarId } shouldContainExactly listOf("old")
        (snapshot.eligible + snapshot.inaccessible).map { it.accessToken } shouldContainExactly
            listOf("access", "access")
    }

    test("選択planと監査snapshotの文字列表現へaccess tokenを露出しない") {
        val secret = "plain-text-secret-token"
        val connection = SelectedCalendarConnection(
            userCalendarUuid = com.crowdodge.user.domain.model.UserCalendarUuid.new(),
            userUuid = userUuid,
            googleCalendarId = "one",
            accessToken = secret,
        )
        val plan = CalendarSelectionPlan(userUuid, listOf(connection), emptyList(), emptyList())
        val snapshot = CalendarSelectionMaintenanceSnapshot(listOf(connection), emptyList())

        plan.toString().contains(secret) shouldBe false
        snapshot.toString().contains(secret) shouldBe false
    }

    test("全選択をGoogleアカウント単位で取得して各ユーザーの一時tokenを付与する") {
        val otherUserUuid = UserUuid(Uuid.parse("10000000-0000-0000-0000-000000000002"))
        val repository = FakeCalendarRepository(
            mutableListOf(
                selectedCalendar(userUuid, "one"),
                selectedCalendar(otherUserUuid, "two"),
            ),
        )
        val requestedUsers = mutableListOf<UserUuid>()
        val tokenRequestedUsers = mutableListOf<UserUuid>()
        val tokens = mapOf(userUuid to "first-access", otherUserUuid to "second-access")
        val service = UserCalendarSelectionService(
            calendarList = GoogleCalendarListGateway { requestedUserUuid ->
                requestedUsers += requestedUserUuid
                listOf(
                    GoogleCalendarListItem(
                        id = if (requestedUserUuid == userUuid) "one" else "two",
                        name = "calendar",
                        color = null,
                        primary = false,
                        accessRole = GoogleCalendarAccessRole.OWNER,
                    ),
                ).right()
            },
            accessTokens = tokenProvider {
                tokenRequestedUsers += it
                tokens.getValue(it).right()
            },
            calendars = repository,
            transactions = RecordingTransactions(),
            publisher = RecordingPublisher(RecordingTransactions()),
            clock = FixedSelectionClock(now),
        )

        val snapshot = service.inspectAllSelected().getOrNull()!!

        requestedUsers shouldContainExactly listOf(userUuid, otherUserUuid)
        tokenRequestedUsers shouldContainExactly listOf(userUuid, otherUserUuid)
        snapshot.eligible.map { it.accessToken } shouldContainExactly listOf("first-access", "second-access")
    }

    test("選択が空なら外部APIを呼ばず空の監査snapshotを返す") {
        val service = UserCalendarSelectionService(
            calendarList = GoogleCalendarListGateway { error("呼ばれない") },
            accessTokens = tokenProvider { error("呼ばれない") },
            calendars = FakeCalendarRepository(mutableListOf()),
            transactions = RecordingTransactions(),
            publisher = RecordingPublisher(RecordingTransactions()),
            clock = FixedSelectionClock(now),
        )

        service.inspectAllSelected().getOrNull() shouldBe
            CalendarSelectionMaintenanceSnapshot(emptyList(), emptyList())
    }

    test("全選択監査で資格情報を取得できなければ失敗を返す") {
        val repository = FakeCalendarRepository(mutableListOf(selectedCalendar(userUuid, "one")))
        val service = UserCalendarSelectionService(
            calendarList = GoogleCalendarListGateway { error("呼ばれない") },
            accessTokens = tokenProvider {
                com.crowdodge.user.domain.error.UserError.ExternalError.GoogleOAuthError.left()
            },
            calendars = repository,
            transactions = RecordingTransactions(),
            publisher = RecordingPublisher(RecordingTransactions()),
            clock = FixedSelectionClock(now),
        )

        service.inspectAllSelected().leftOrNull() shouldBe
            com.crowdodge.user.domain.error.UserError.ExternalError.GoogleOAuthError
    }
})

private data class SelectionFixture(
    val service: UserCalendarSelectionService,
    val repository: FakeCalendarRepository,
    val transactions: RecordingTransactions,
    val publisher: RecordingPublisher,
    val operations: List<String>,
)

private fun fixture(
    userUuid: UserUuid,
    now: Instant,
    selectedIds: List<String> = emptyList(),
): SelectionFixture {
    val operations = mutableListOf<String>()
    val repository = FakeCalendarRepository(
        selectedIds.map { id ->
            arrow.core.raise.either {
                UserCalendar.select(userUuid, googleCalendarId(id))
            }.getOrNull()!!
        }.toMutableList(),
        operations,
    )
    val transactions = RecordingTransactions()
    val publisher = RecordingPublisher(transactions, operations)
    val available = listOf("one", "two", "three", "four").map {
        GoogleCalendarListItem(it, it, null, false, GoogleCalendarAccessRole.OWNER)
    } + GoogleCalendarListItem("reader", "reader", null, false, GoogleCalendarAccessRole.READER)
    val gateway = GoogleCalendarListGateway { available.right() }
    val service = UserCalendarSelectionService(
        gateway,
        object : GoogleAccessTokenProvider(
            credentials = errorRepository(),
            refreshGateway = { error("unused") },
            transactions = transactions,
            clock = Clock.System,
        ) {
            override suspend fun get(userUuid: UserUuid) = "access".right()
        },
        repository,
        transactions,
        publisher,
        FixedSelectionClock(now),
    )
    return SelectionFixture(service, repository, transactions, publisher, operations)
}

private fun selectedCalendar(userUuid: UserUuid, id: String): UserCalendar = arrow.core.raise.either {
    UserCalendar.select(userUuid, googleCalendarId(id))
}.getOrNull()!!

private fun tokenProvider(
    getToken: suspend (UserUuid) -> arrow.core.Either<
        com.crowdodge.user.domain.error.UserError.ExternalError,
        String,
        >,
) = object : GoogleAccessTokenProvider(
    credentials = errorRepository(),
    refreshGateway = { error("unused") },
    transactions = RecordingTransactions(),
    clock = Clock.System,
) {
    override suspend fun get(userUuid: UserUuid) = getToken(userUuid)
}

private fun errorRepository() = object : com.crowdodge.user.domain.repository.UserGoogleCredentialRepository {
    override suspend fun findByUserUuid(userUuid: UserUuid) = error("unused")
    override suspend fun upsert(credential: com.crowdodge.user.domain.model.UserGoogleCredential) = error("unused")
    override suspend fun updateAccessToken(
        userUuid: UserUuid,
        accessToken: com.crowdodge.user.domain.model.GoogleAccessToken,
        accessTokenExpiresAt: Instant,
    ) = error("unused")
}

private class FakeCalendarRepository(
    val items: MutableList<UserCalendar>,
    private val operations: MutableList<String> = mutableListOf(),
) : UserCalendarRepository {
    var replaceCount = 0
    override suspend fun create(userCalendar: UserCalendar) = Unit.right()
    override suspend fun delete(
        userUuid: UserUuid,
        userCalendarUuid: com.crowdodge.user.domain.model.UserCalendarUuid,
    ) {
        items.removeIf { it.userUuid == userUuid && it.userCalendarUuid == userCalendarUuid }
    }
    override suspend fun findByUserUuid(userUuid: UserUuid) = items.filter { it.userUuid == userUuid }
    override suspend fun findAll() = items.toList()
    override suspend fun replaceForUser(
        userUuid: UserUuid,
        calendars: List<UserCalendar>,
    ): arrow.core.Either<com.crowdodge.user.domain.error.UserError.ConflictError.DuplicateCalendar, Unit> {
        operations += "replace"
        replaceCount++
        items.removeIf { it.userUuid == userUuid }
        items += calendars
        return Unit.right()
    }
}

private class RecordingTransactions : TransactionRunner {
    var writeCount = 0
    var failedWriteCount = 0
    var inside = false
    override suspend fun <T> inTransaction(block: suspend () -> T): T {
        writeCount++
        inside = true
        return try {
            block()
        } catch (exception: Exception) {
            failedWriteCount++
            throw exception
        } finally {
            inside = false
        }
    }
    override suspend fun <T> readOnly(block: suspend () -> T): T = block()
}

private class RecordingPublisher(
    private val transactions: RecordingTransactions,
    private val operations: MutableList<String> = mutableListOf(),
) : DomainEventPublisher {
    val events = mutableListOf<CalendarInitialSyncRequested>()
    var publishedInsideTransaction = false
    override suspend fun publish(event: DomainEvent) {
        operations += "publish"
        publishedInsideTransaction = transactions.inside
        events += event as CalendarInitialSyncRequested
    }
}

private class FixedSelectionClock(private val instant: Instant) : Clock {
    override fun now(): Instant = instant
}
