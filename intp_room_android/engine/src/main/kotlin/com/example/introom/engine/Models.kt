package com.example.introom.engine

/*
 * 게임 상태 데이터 모델.
 *
 * 핵심 원칙(명세서 4장): 세계의 사실(world truth), 플레이어가 "실제로 관찰한" 사실(Observation),
 * 책/컴퓨터가 제공하는 "정보"(InfoEntry), 플레이어 자신의 해석(Hypothesis)을 하나로 합치지 않고
 * 끝까지 분리해서 저장한다. 어느 레코드도 "정답"을 자동으로 확정해주지 않는다.
 *
 * Python 버전(app/engine/models.py)을 그대로 포팅했다 — 필드명·구조·기본값을 전부 맞췄다.
 */

// 하루는 7개 시간대로 진행된다. 매일 전부 플레이하는 건 아니고, 저녁/밤 이후엔 자동으로 잠들 수 있다.
val PERIODS = listOf("dawn", "morning", "day1", "day2", "sunset", "evening", "night")
val PERIOD_LABELS = mapOf(
    "dawn" to "새벽", "morning" to "아침", "day1" to "낮", "day2" to "오후",
    "sunset" to "해질녘", "evening" to "저녁", "night" to "밤",
)

// 0턴 행동이 한 시간대 안에서 무한 반복되며 콘텐츠를 소모하지 않도록 거는 상한.
// 숫자 자체는 플레이어에게 보여주지 않는다 (명세서 3장).
const val ZERO_TURN_REPEAT_LIMIT = 3

// 세이브 형식 버전. 2: 창문 관찰 이력/사실 플래그/지식 필드 추가.
const val STATE_VERSION = 2

val HYPOTHESIS_STATUSES = listOf("guess", "possible", "likely", "near_certain", "revised", "discarded")
val HYPOTHESIS_STATUS_LABELS = mapOf(
    "guess" to "추측", "possible" to "가능성 있음", "likely" to "유력",
    "near_certain" to "거의 확실", "revised" to "재검토됨", "discarded" to "폐기됨",
)

/** 플레이어가 '직접' 본 것. 세계에 실제로 일어난 일 중 관찰로 포착된 부분만 들어간다. */
data class Observation(
    val id: String,
    val thread: String,
    val day: Int,
    val period: String,
    val text: String,
    val tags: Map<String, Any?> = emptyMap(),
)

/** 책/컴퓨터가 제공하는 정보. 직접 관찰이 아니라 '정보원'을 통해 얻은 지식/후보다. */
data class InfoEntry(
    val id: String,
    val thread: String,
    val day: Int,
    val source: String, // "book" | "computer"
    val text: String,
)

data class HypothesisRevision(
    val day: Int,
    val period: String,
    val status: String,
    val text: String,
    val reason: String = "",
)

/** 플레이어 자신의 해석. 관찰/정보와는 별개 레코드이며, 틀려도 삭제하지 않고 이력을 남긴다. */
data class Hypothesis(
    val id: String,
    val thread: String,
    val createdDay: Int,
    var text: String,
    var status: String,
    val revisions: MutableList<HypothesisRevision> = mutableListOf(),
) {
    fun latestText(): String = revisions.lastOrNull()?.text ?: text
}

/** 능력치 상승이 아니라 '발견' 그 자체. 쌍안경 같은 물건, 이삿짐 속 단서 등. */
data class Discovery(
    val id: String,
    val day: Int,
    val period: String,
    val text: String,
)

/** 탐구 하나(예: 밤바다 불빛)의 진행 상태. 메모벽 생존 여부 판정에 쓰인다. */
data class ThreadState(
    val id: String,
    var observationCount: Int = 0,
    var revisionCount: Int = 0,
    var exitReady: Boolean = false, // 이 탐구가 "외부 관찰 필요" 단계에 도달했는지
)

/** 게임 안 시점 (day, period). 창문 관찰 이력/사실 플래그/지식 출처에 쓴다. */
data class TimePoint(val day: Int, val period: String)

/** REC-01: 창문 문장 하나의 최초/최근 관찰 시점과 횟수. */
data class ObservationHistory(
    val firstSeen: TimePoint,
    var lastSeen: TimePoint,
    var count: Int = 0,
)

/** REC-02: 관찰로 생긴 사실 플래그. 문장 출력 횟수가 아니라 "무엇을, 언제 봤는지"를 남긴다. */
data class FactFlag(
    val first: TimePoint,
    val days: MutableList<Int> = mutableListOf(),
    val seenAt: MutableList<TimePoint> = mutableListOf(),
)

data class KnowledgeSource(val id: String, val day: Int, val period: String)

/** KNW-01/02: 지식. 1차 범위에서는 "접함(encountered)"만 기록하고, 같은 지식은 중복 생성하지 않는다. */
data class KnowledgeEntry(
    var status: String = "encountered", // encountered | understood | applied (행동으로만 오른다)
    val sources: MutableList<KnowledgeSource> = mutableListOf(),
)

/** 준비된 질문의 상태 변경 이력 한 줄. */
data class QuestionStateChange(val state: String = "", val day: Int = 0, val period: String = "")

/** QST-01/REC-04: 떠오른 질문. 상태는 떠오름(arisen)·붙잡음(held)·다시 생각함(revisited) — 완료/미완료 없음. */
data class QuestionRecord(
    var state: String = "arisen",
    val first: TimePoint = TimePoint(0, ""),
    val source: String = "",
    val history: MutableList<QuestionStateChange> = mutableListOf(),
)

/** 파도 기록 해석 당시의 근거 (어떤 기록을 보고 판단했는지). */
data class WaveBasis(val measuredDays: List<Int> = emptyList(), val roughCounts: Int = 0, val spread: Double? = null)

/** 탐구 행동 한 번의 기록. 행동마다 쓰는 필드만 채운다 (측정: seconds / 세기: low·high / 해석: choice·status·basis). */
data class ActionEntry(
    val day: Int = 0,
    val period: String = "",
    val seconds: Double? = null,
    val low: Int? = null,
    val high: Int? = null,
    val choice: Int? = null,
    val status: String? = null,
    val basis: WaveBasis? = null,
)

/** 탐구 행동 하나(예: 파도 측정)의 수행 횟수와 이력. 관찰·질문·지식과 독립적으로 저장된다 (5장). */
data class ActionRecord(var count: Int = 0, val history: MutableList<ActionEntry> = mutableListOf())

/** 자동 연결 한 단계의 기록 (명세서 261012 4장). evidence 는 실제 플레이 기록 ID 만 담는다. */
data class ConnectionStageRecord(
    val stage: Int = 0,
    val day: Int = 0,
    val period: String = "",
    val triggerObservationId: String = "",
    val evidence: List<String> = emptyList(),
)

/** 자동 연결 하나의 현재 단계와 이력. */
data class ConnectionRecord(
    var stage: Int = 0,
    val firstDay: Int = 0,
    var lastDay: Int = 0,
    var triggerObservationId: String = "",
    var evidence: MutableList<String> = mutableListOf(),
    val history: MutableList<ConnectionStageRecord> = mutableListOf(),
)

data class GameState(
    var version: Int = STATE_VERSION, // 2: 창문 관찰 이력/사실 플래그/지식 필드 추가 (v1 세이브는 그대로 로드됨)
    var day: Int = 1,
    var period: String = "morning",
    var seed: Long = 1,

    // 세계의 "사실"과 플레이어가 "관찰한 사실"을 분리해서 기록한다.
    val worldLog: MutableList<String> = mutableListOf(),         // 실제로 일어난 일 (세이브에는 들어가되 UI엔 노출 안 함)
    val observations: MutableList<Observation> = mutableListOf(),
    val infos: MutableList<InfoEntry> = mutableListOf(),
    val hypotheses: MutableList<Hypothesis> = mutableListOf(),
    val discoveries: MutableList<Discovery> = mutableListOf(),
    val memoWall: MutableMap<String, String> = mutableMapOf(),    // thread_id -> 현재 생존 질문 문구
    val threads: MutableMap<String, ThreadState> = mutableMapOf(),

    val items: MutableList<String> = mutableListOf(),             // 예: "쌍안경"
    val booksRead: MutableList<String> = mutableListOf(),
    val searches: MutableList<Map<String, Any?>> = mutableListOf(),
    var windowResolution: Int = 0,          // (v1 호환용, 더 이상 쓰지 않음 — 시작부터 5개 대상 선택 가능)
    var binocularsFumbledOnce: Boolean = false, // 쌍안경 첫 사용: 좁은 시야 때문에 한 번 놓치는 연출을 이미 겪었는지

    val zeroTurnCounts: MutableMap<String, Int> = mutableMapOf(), // "{object}|{day}|{period}" -> 횟수
    val interactionLog: MutableList<InteractionRecord> = mutableListOf(),

    // ---- 창문 관찰·기록 (명세서 261011). 세이브 v1 에는 없는 필드라 기본값으로 채워진다.
    val observationHistory: MutableMap<String, ObservationHistory> = mutableMapOf(), // 문장ID -> 이력
    val factFlags: MutableMap<String, FactFlag> = mutableMapOf(),                    // 사실 플래그 -> 관찰 시점
    val knowledge: MutableMap<String, KnowledgeEntry> = mutableMapOf(),              // 지식ID -> 상태/습득 경로
    var windowRecent: MutableList<String> = mutableListOf(),                         // 최근 출력한 창문 문장 ID (반복 감소용)
    // ---- 자동 연결 (명세서 261012). 이 필드가 없는 기존 v2 세이브는 빈 이력으로 로드된다.
    val connections: MutableMap<String, ConnectionRecord> = mutableMapOf(),          // 연결ID -> 단계/근거/이력
    // ---- 2차 범위 (Inquiry.kt): 준비된 질문, 탐구 행동 이력
    val questions: MutableMap<String, QuestionRecord> = mutableMapOf(),              // 질문ID -> 상태/이력
    val actionsDone: MutableMap<String, ActionRecord> = mutableMapOf(),              // 탐구 행동ID -> 횟수/이력
    // 3차 범위 — 데이터 설계만 선반영 (아직 이 필드를 채우는 기능 없음)
    val npcRelations: MutableMap<String, MutableMap<String, Boolean>> = mutableMapOf(), // 예: {"dog": {"name_known": true}}
    val freeNotes: MutableList<String> = mutableListOf(),                            // 자유 메모

    var exitUnlocked: Boolean = false, // 문이 [확인하러 간다]로 바뀌었는지
    var ended: Boolean = false,        // Vertical Slice End 에 도달했는지

    val log: MutableList<String> = mutableListOf(), // 화면에 보여줄 최근 서술 로그
)

data class InteractionRecord(val objectId: String, val action: String, val day: Int, val period: String)
