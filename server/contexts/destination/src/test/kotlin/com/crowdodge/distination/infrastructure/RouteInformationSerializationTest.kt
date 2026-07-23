package com.crowdodge.distination.infrastructure

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.Json

class RouteInformationSerializationTest : FunSpec({
    test("途中駅を含む経路情報をencodeしてdecodeできる") {
        val route = RouteInformation(
            routeSteps = listOf(
                RouteStep(
                    fromName = "新宿駅",
                    toName = "御茶ノ水駅",
                    lineName = "JR中央線",
                    moveType = "local_train",
                    durationMin = 10,
                    distanceMeter = 8_000,
                    callingAt = listOf("四ツ谷駅", "御茶ノ水駅"),
                ),
            ),
        )

        val encoded = Json.encodeToString(RouteInformation.serializer(), route)
        val decoded = Json.decodeFromString(RouteInformation.serializer(), encoded)

        decoded shouldBe route
    }

    test("callingAtがない既存JSONをemptyListとしてdecodeできる") {
        val oldJson = """
            {
              "routeSteps": [{
                "fromName": "新宿駅",
                "toName": "御茶ノ水駅",
                "lineName": "JR中央線",
                "moveType": "local_train",
                "durationMin": 10,
                "distanceMeter": 8000
              }]
            }
        """.trimIndent()

        val decoded = Json.decodeFromString(RouteInformation.serializer(), oldJson)

        decoded.routeSteps.single().callingAt shouldBe emptyList()
    }
})
