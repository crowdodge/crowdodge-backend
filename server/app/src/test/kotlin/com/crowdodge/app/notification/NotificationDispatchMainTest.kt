package com.crowdodge.app.notification

import com.crowdodge.notification.application.dispatch.DispatchResult
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NotificationDispatchMainTest : FunSpec({
    test("正常終了は exit code 0") {
        runNotificationDispatch { DispatchResult(completed = 2, failed = 0, canceled = 1) } shouldBe 0
    }

    test("送信失敗があっても Job 自体は成功（exit code 0）") {
        runNotificationDispatch { DispatchResult(completed = 1, failed = 3, canceled = 0) } shouldBe 0
    }

    test("実行前に例外で落ちたら exit code 1") {
        runNotificationDispatch { error("DB unreachable") } shouldBe 1
    }
})
