package com.example.intpgame.engine

/**
 * 게임 엔진의 데이터 모델. Android 의존성이 없는 순수 Kotlin 이라 JVM 단위 테스트로 그대로 검증할 수 있다.
 * 파이썬(FastAPI) 버전의 models.py / JSON 콘텐츠와 1:1 로 대응한다.
 */

// ---------------------------------------------------------------- 콘텐츠 (assets/*.json)

data class Effects(
    val stats: Map<String, Int> = emptyMap(),
    val status: Map<String, Int> = emptyMap(),
    val interests: Map<String, Int> = emptyMap(),
    val unlockLocations: List<String> = emptyList(),
    val discover: List<String> = emptyList(),
    val advanceTime: Int = 0,
)

data class ActionDef(
    val id: String,
    val label: String,
    val hint: String,
    val timeCost: Int,
    val skipToNextDay: Boolean,
    val effects: Effects,
    val logs: List<String>,
)

data class ObjectDef(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val actions: List<String>,
)

data class LocationDef(
    val id: String,
    val name: String,
    val description: String,
    val objects: List<ObjectDef>,
)

data class Conditions(
    val lastAction: List<String>? = null,
    val location: String? = null,
    val periodIn: List<String>? = null,
    val dayGte: Int = 1,
    val actionCountsGte: Map<String, Int> = emptyMap(),
    val interestsGte: Map<String, Int> = emptyMap(),
    val statsGte: Map<String, Int> = emptyMap(),
    val statusGte: Map<String, Int> = emptyMap(),
    val statusLte: Map<String, Int> = emptyMap(),
    val infoPresent: List<String> = emptyList(),
    val infoAbsent: List<String> = emptyList(),
    val chance: Double? = null,
)

data class ChoiceDef(
    val text: String,
    val result: String,
    val effects: Effects,
)

data class EventDef(
    val id: String,
    val title: String,
    val description: String,
    val once: Boolean,
    val cooldownDays: Int,
    val conditions: Conditions,
    val choices: List<ChoiceDef>,
)

/** 표시용 이름. 맵은 JSON 에 적힌 순서를 유지한다(LinkedHashMap). */
data class Labels(
    val stats: Map<String, String>,
    val status: Map<String, String>,
    val interests: Map<String, String>,
    val periods: Map<String, String>,
)

data class GameContent(
    val labels: Labels,
    val locations: Map<String, LocationDef>,
    val actions: Map<String, ActionDef>,
    val events: List<EventDef>,
)

// ---------------------------------------------------------------- 진행 상태 (저장 대상)

/** 엔진이 직접 수정하는 가변 상태. UI 는 이걸 직접 보지 않고 [GameSnapshot] 만 본다. */
class GameState {
    val stats: MutableMap<String, Int> = linkedMapOf(
        "thinking" to 10, "execution" to 5, "curiosity" to 10,
        "creativity" to 8, "social" to 3, "reality" to 5,
    )
    val status: MutableMap<String, Int> = linkedMapOf(
        "energy" to 100, "hyperfocus" to 0, "money" to 50000, "food" to 3,
    )
    var day: Int = 1
    var period: String = "morning"
    var currentLocation: String = "room"
    val unlockedLocations: MutableList<String> = mutableListOf("room")
    val actionCounts: MutableMap<String, Int> = linkedMapOf()
    val interests: MutableMap<String, Int> = linkedMapOf()
    val discoveredInfo: MutableList<String> = mutableListOf()
    var activeEventId: String? = null
    val logs: MutableList<String> = mutableListOf()

    // 엔진 보조 필드
    var lastAction: String? = null
    val seenEvents: MutableList<String> = mutableListOf()
    val eventLastDay: MutableMap<String, Int> = linkedMapOf()
    var pendingAdvance: Int = 0
    var pendingSlept: Boolean = false

    val energy: Int get() = status.getValue("energy")
    val hyperfocus: Int get() = status.getValue("hyperfocus")
    val money: Int get() = status.getValue("money")
    val food: Int get() = status.getValue("food")
}

/** 규칙 위반(기력 부족 등). message 는 플레이어에게 그대로 보여줄 수 있다. */
class GameError(message: String, val code: Int = 400) : Exception(message)

// ---------------------------------------------------------------- 화면용 불변 스냅샷

data class ActionView(
    val id: String,
    val label: String,
    val hint: String,
    val cost: String,
    val enabled: Boolean,
    val reason: String,
)

data class ObjectView(
    val id: String,
    val name: String,
    val icon: String,
    val description: String,
    val actions: List<ActionView>,
)

data class LocationTab(val id: String, val name: String)

data class EventPayload(
    val id: String,
    val title: String,
    val description: String,
    val choices: List<String>,   // 선택지 글자만. 효과는 화면에 노출하지 않는다.
)

data class LabeledValue(val key: String, val label: String, val value: Int)

data class GameSnapshot(
    val day: Int,
    val period: String,
    val periodLabel: String,
    val energy: Int,
    val hyperfocus: Int,
    val money: Int,
    val food: Int,
    val stats: List<LabeledValue>,
    val interests: List<LabeledValue>,
    val discoveredInfo: List<String>,
    val logs: List<String>,
    val locationId: String,
    val locationName: String,
    val locationDescription: String,
    val objects: List<ObjectView>,
    val locations: List<LocationTab>,
    val activeEvent: EventPayload?,
)

// ---------------------------------------------------------------- 한 번의 조작 결과

data class Changes(
    val stats: Map<String, Int> = emptyMap(),
    val status: Map<String, Int> = emptyMap(),
    val interests: Map<String, Int> = emptyMap(),
    val unlocked: List<String> = emptyList(),
    val discovered: List<String> = emptyList(),
)

data class StepResult(
    val changes: Changes,
    val event: EventPayload?,
    val newDay: Boolean,
    val newLogs: List<String>,
    val resultText: String? = null,
)
