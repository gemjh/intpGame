package com.example.introom.engine

import java.util.UUID
import kotlin.random.Random

/*
 * 게임 엔진. 명세서 3~5장의 규칙을 구현한다.
 *
 * 핵심 규칙:
 * - 행동은 turnCost 0(잠깐 보기류) 또는 1(진짜 조사)이다. 0턴 행동은 한 시간대 안에서 제한 횟수
 *   이후 "아까 충분히 봤다"류로 수렴해서 콘텐츠를 무한정 소모하지 못하게 한다.
 * - 세계는 (day, period, seed) 순수 함수로 결정되는 '사실'과, 플레이어가 실제로 관찰해서 생긴
 *   Observation 을 분리한다. 플레이어가 안 본 사건은 관찰 기록에 들어가지 않는다.
 * - 관찰(Observation) / 정보(InfoEntry) / 가설(Hypothesis) 은 끝까지 분리 저장한다.
 *
 * Python 버전(app/engine/engine.py)의 포트. 로직·조건·수치를 전부 그대로 옮겼다 — 이 라운드의
 * 목표가 "콘텐츠/로직은 새로 안 만들고 그대로 가져오는 것"이기 때문이다.
 */

class GameError(message: String, val code: Int = 400) : Exception(message) {
    val gameMessage: String get() = message ?: ""
}

typealias Payload = Map<String, Any?>
typealias Result = Map<String, Any?>

fun newGame(seed: Long? = null): GameState {
    val s = GameState(seed = seed ?: Random.nextLong(1, 1_000_000))
    log(s, "낯선 방. 창밖으로 바다가 보인다. 아직 다 풀지 못한 이삿짐 상자들이 쌓여 있다.")
    return s
}

private fun log(state: GameState, text: String) {
    val label = PERIOD_LABELS[state.period] ?: state.period
    state.log.add("[D${state.day} $label] $text")
    if (state.log.size > 300) {
        val trimmed = state.log.takeLast(300)
        state.log.clear()
        state.log.addAll(trimmed)
    }
}

private fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(10)

private fun thread(state: GameState, threadId: String): ThreadState =
    state.threads.getOrPut(threadId) { ThreadState(id = threadId) }

/** true 면 이번엔 '아까 충분히 봤다'류로 보여줘야 함 (제한 초과). */
private fun checkZeroTurn(state: GameState, objectId: String): Boolean {
    val key = "$objectId|${state.day}|${state.period}"
    val n = state.zeroTurnCounts.getOrDefault(key, 0)
    state.zeroTurnCounts[key] = n + 1
    return n >= ZERO_TURN_REPEAT_LIMIT
}

private fun advanceTime(state: GameState, cost: Int) {
    if (cost <= 0) return
    var idx = PERIODS.indexOf(state.period)
    idx += cost
    if (idx < PERIODS.size) {
        state.period = PERIODS[idx]
        return
    }
    // 밤을 넘기면 자동으로 잠든다 - 다음 날 새벽 또는 아침에 깨어난다.
    state.day += 1
    state.period = listOf("dawn", "morning").random()
    log(state, "── 어느새 잠들었다. Day ${state.day} ${PERIOD_LABELS[state.period]}, 눈을 떴다. ──")
}

private fun recordInteraction(state: GameState, objectId: String, actionId: String) {
    state.interactionLog.add(InteractionRecord(objectId, actionId, state.day, state.period))
}

private fun addObservation(state: GameState, threadId: String, text: String, tags: Map<String, Any?> = emptyMap()): Observation {
    val obs = Observation(id = newId(), thread = threadId, day = state.day, period = state.period, text = text, tags = tags)
    state.observations.add(obs)
    val t = thread(state, threadId)
    t.observationCount += 1
    updateMemoWall(state, threadId)
    updateExitReady(state, threadId)
    return obs
}

private fun updateMemoWall(state: GameState, threadId: String) {
    val t = thread(state, threadId)
    val stages = MEMO_WALL_QUESTION_STAGES[threadId] ?: return
    val thresh = MEMO_WALL_THRESHOLD
    if (t.observationCount >= thresh.observations && t.revisionCount >= thresh.revisions) {
        val stageIdx = minOf(t.revisionCount, stages.size - 1)
        state.memoWall[threadId] = stages[stageIdx]
    }
}

private fun updateExitReady(state: GameState, threadId: String) {
    val t = thread(state, threadId)
    val thresh = EXIT_READY_THRESHOLD
    if (!t.exitReady && t.observationCount >= thresh.observations && t.revisionCount >= thresh.revisions) {
        t.exitReady = true
        state.exitUnlocked = true
        log(state, "이젠 여기서 더 볼 수 있는 게 없는 것 같다. 직접 나가서 확인해야 할 것 같다.")
    }
}

// ------------------------------------------------------------------ 상호작용 진입점
fun interact(state: GameState, objectId: String, actionId: String, playerPos: Pair<Int, Int>? = null, payload: Payload = emptyMap()): Result {
    if (state.ended) throw GameError("이미 끝난 세션이야.", 409)
    if (playerPos != null) {
        val (px, py) = playerPos
        if (!isAdjacentToObject(px, py, objectId)) throw GameError("그 오브젝트 근처로 가야 상호작용할 수 있어.", 400)
    }

    val handler = HANDLERS[objectId to actionId] ?: throw GameError("알 수 없는 상호작용이야.", 404)
    val result = handler(state, payload)
    recordInteraction(state, objectId, actionId)
    return result
}

// ------------------------------------------------------------------ 창문
private fun hWindowGlance(state: GameState, payload: Payload): Result {
    val text = if (checkZeroTurn(state, "window")) ENOUGH_ALREADY.getValue("window") else WINDOW_GLANCE_LINES.random()
    log(state, text)
    return mapOf("text" to text, "turn_cost" to 0)
}

// 기존 세계 이벤트(밤바다 불빛/낮달/아저씨 귀환/고양이)는 세계의 사실(World.kt)이 그 시점에 실제로
// 일어나고 있을 때, 그 대상을 처음 본 그날 한 번 우선해서 보여준다. 그 외에는 148문장 후보에서 고른다.
private fun legacyWorldEvent(state: GameState, subject: String?): MutableMap<String, Any?>? {
    val day = state.day; val period = state.period; val seed = state.seed
    fun looksAt(s: String) = subject == null || subject == WINDOW_ANY || subject == s
    fun seenToday(threadId: String) = state.observations.any { it.thread == threadId && it.day == day }

    if (looksAt("sea") && nightLightVisible(day, period, seed) && !seenToday("night_light")) {
        val t = thread(state, "night_light")
        val variant = nightLightVariant(day, seed)
        val text: String
        val tags: Map<String, Any?>
        if (t.observationCount == 0) {
            text = "밤바다 오른쪽에서 흰 불빛을 봤다. 깜빡이는 것처럼 보인다."
            tags = mapOf("color" to "white", "side" to variant.side, "blinking" to variant.blinking)
        } else {
            val stageIdx = minOf(t.observationCount, NIGHT_LIGHT_REOBSERVE_STAGES.size - 1)
            text = NIGHT_LIGHT_REOBSERVE_STAGES[stageIdx]
            tags = mapOf("blinking" to variant.blinking, "angle_shift" to variant.angleShift)
        }
        val obs = addObservation(state, "night_light", text, tags)
        return mutableMapOf("text" to text, "subject" to "sea", "thread" to "night_light", "observation_id" to obs.id)
    }

    if (looksAt("sky") && daytimeMoonVisible(day, period, seed) && !seenToday("moon")) {
        val t = thread(state, "moon")
        val text = if (t.observationCount == 0) {
            "낮 하늘에 달이 떠 있다. '달? 낮인데.' 하는 생각이 스쳤다."
        } else {
            "왜 낮에 달이 있으면 이상하다고 생각했는지, 그 생각이 계속 맴돈다."
        }
        val obs = addObservation(state, "moon", text)
        recordFacts(state, LEGACY_EFFECTS.getValue("moon") + "subject:sky")
        return mutableMapOf("text" to text, "subject" to "sky", "thread" to "moon", "observation_id" to obs.id)
    }

    if (looksAt("shop") && period in setOf("day1", "day2") && ownerReturnsAtDay(day, seed)) {
        val key = "rest_return|$day"
        if (key !in state.zeroTurnCounts) { // 하루에 한 번만 기록
            state.zeroTurnCounts[key] = 1
            val status = restaurantStatus(day, period, seed)
            val text = "아저씨가 상자를 들고 돌아왔다." // 못 본 출항은 적지 않는다 (관찰한 것만 기록)
            val obs = addObservation(state, "restaurant", text, mapOf("status" to status))
            recordFacts(state, LEGACY_EFFECTS.getValue("restaurant") + "subject:shop")
            return mutableMapOf("text" to text, "subject" to "shop", "thread" to "restaurant", "observation_id" to obs.id)
        }
    }

    if (looksAt("yard") && catInYard(day, period, seed) && "cat" !in state.factFlags) {
        // 처음 보는 고양이. 이 관찰이 있어야 YARD-001("지난번에도 저 길로...")이 출현할 수 있다.
        val text = MEANINGLESS_WINDOW_EVENTS.getValue("cat")
        recordFacts(state, LEGACY_EFFECTS.getValue("cat") + "subject:yard")
        return mutableMapOf("text" to text, "subject" to "yard", "thread" to null)
    }
    return null
}

private fun windowObserveWorld(state: GameState, subject: String?): MutableMap<String, Any?> {
    legacyWorldEvent(state, subject)?.let { legacy ->
        legacy.putIfAbsent("observation_key", null)
        return legacy
    }

    val (pickedSubject, obs) = pickWindowObservation(state, subject)
    if (obs == null) {
        // WIN-05: 후보가 없으면 중립 문장. 턴은 쓰지만 발견/지식/질문은 만들지 않는다.
        val text = pickedSubject?.let { NEUTRAL_LINES[it] } ?: NEUTRAL_LINE_ANY
        return mutableMapOf("text" to text, "subject" to pickedSubject, "thread" to null, "observation_key" to null)
    }
    val rec = recordWindowObservation(state, obs)
    return mutableMapOf(
        "text" to renderWindowText(state, obs.text),
        "subject" to obs.subject,
        "thread" to null,
        "observation_key" to obs.id,
        "first_seen" to rec.firstSeen,
        "new_knowledge" to rec.newKnowledge,
    )
}

private val LEGACY_REGION_ALIASES = mapOf("restaurant" to "shop")

/** WIN-01~03: 대상(바다/하늘/횟집/강아지/앞마당) 또는 그냥 바라본다(any). 실행마다 정확히 1턴. */
private fun hWindowObserve(state: GameState, payload: Payload): Result {
    var subject = (payload["subject"] ?: payload["region"]) as? String
    subject = LEGACY_REGION_ALIASES[subject] ?: subject
    if (subject != null && subject != WINDOW_ANY && subject !in WINDOW_SUBJECTS) {
        throw GameError("그쪽은 창문에서 보이지 않아.", 400)
    }
    val result = windowObserveWorld(state, subject)
    log(state, result["text"] as String)
    result["turn_cost"] = 1
    advanceTime(state, 1) // 시간대/날짜 갱신은 기존 규칙에 위임 (WIN-04)
    return result
}

/**
 * 쌍안경으로 확대 관찰. 맨눈으로 먼저 위치를 확인해야 하고(관찰 방법의 확장이지 능력치 증가가 아님),
 * 첫 사용에서는 좁은 시야 때문에 대상을 놓쳤다가 다시 맞추는 연출을 한 번 거친다.
 */
private fun hWindowZoom(state: GameState, payload: Payload): Result {
    if ("쌍안경" !in state.items) throw GameError("쌍안경이 없다.", 400)

    val day = state.day; val period = state.period; val seed = state.seed
    if (!nightLightVisible(day, period, seed)) {
        val text = "쌍안경을 들었지만 지금은 딱히 당겨 볼 게 없다."
        log(state, text)
        advanceTime(state, 1)
        return mapOf("text" to text, "turn_cost" to 1, "thread" to null)
    }

    val t = thread(state, "night_light")
    if (t.observationCount == 0) {
        // 맨눈 관찰 없이 쌍안경부터 들이댄 경우 - 위치를 몰라서 놓친다.
        val text = BINOCULARS_NEED_NAKED_EYE_FIRST
        log(state, text)
        advanceTime(state, 1)
        return mapOf("text" to text, "thread" to null, "turn_cost" to 1)
    }

    var prefix = ""
    if (!state.binocularsFumbledOnce) {
        prefix = BINOCULARS_FIRST_USE_FUMBLE
        state.binocularsFumbledOnce = true
    }

    val text = prefix + NIGHT_LIGHT_ZOOM_TEXT
    val obs = addObservation(state, "night_light", text, mapOf("zoomed" to true))
    log(state, text)
    advanceTime(state, 1)
    return mapOf("text" to text, "turn_cost" to 1, "thread" to "night_light", "observation_id" to obs.id)
}

// ------------------------------------------------------------------ 이삿짐 상자
private fun hBoxesOrganize(state: GameState, payload: Payload): Result {
    val count = state.interactionLog.count { it.objectId == "boxes" && it.action == "organize" }
    if (count >= BOX_REVEALS.size) {
        val text = "더 정리할 건 없어 보인다."
        log(state, text)
        advanceTime(state, 1)
        return mapOf("text" to text, "turn_cost" to 1)
    }

    val text = BOX_REVEALS[count]
    log(state, text)
    var gainedBinoculars = false
    if (count == BINOCULARS_REVEAL_INDEX && "쌍안경" !in state.items) {
        state.items.add("쌍안경")
        state.discoveries.add(Discovery(id = newId(), day = state.day, period = state.period, text = "쌍안경을 챙겼다."))
        gainedBinoculars = true
    }
    advanceTime(state, 1)
    return mapOf("text" to text, "turn_cost" to 1, "item_gained" to (if (gainedBinoculars) "쌍안경" else null))
}

// ------------------------------------------------------------------ 책상
private fun hDeskHypothesis(state: GameState, payload: Payload): Result {
    val threadId = payload["thread"] as? String ?: throw GameError("이 주제는 아직 가설을 세울 만한 관찰이 없어.", 400)
    val templateIdx = payload["template_index"] as? Int
    val templates = HYPOTHESIS_TEMPLATES[threadId] ?: throw GameError("이 주제는 아직 가설을 세울 만한 관찰이 없어.", 400)
    val t = thread(state, threadId)
    if (t.observationCount == 0) throw GameError("먼저 관찰부터 해야 가설을 세울 수 있어.", 400)

    val existing = state.hypotheses.firstOrNull { it.thread == threadId }
    val text: String
    val hyp: Hypothesis
    if (existing == null) {
        val idx = if (templateIdx != null && templateIdx in templates.indices) templateIdx else 0
        val tpl = templates[idx]
        hyp = Hypothesis(
            id = newId(), thread = threadId, createdDay = state.day, text = tpl.text, status = tpl.status,
            revisions = mutableListOf(HypothesisRevision(day = state.day, period = state.period, status = tpl.status, text = tpl.text)),
        )
        state.hypotheses.add(hyp)
        text = "책상 앞에 앉아 가설을 적었다: “${tpl.text}”"
    } else {
        // 반례에 따른 재검토. 자동으로 제안되는 재검토 문구를 그대로 쓴다 (틀린 가설은 지우지 않는다).
        val reason = "재관찰 결과와 맞지 않는 부분이 있다."
        val revisedText = if (t.revisionCount == 0) {
            "${existing.latestText()} — 위치 변화 확인 못 함. 재검토 필요."
        } else {
            "${existing.latestText()} (계속 지켜보는 중)"
        }
        existing.revisions.add(HypothesisRevision(day = state.day, period = state.period, status = "revised", text = revisedText, reason = reason))
        existing.status = "revised"
        t.revisionCount += 1
        text = "책상에서 기존 가설을 다시 봤다: “$revisedText”"
        hyp = existing
        updateMemoWall(state, threadId)
        updateExitReady(state, threadId)
    }

    log(state, text)
    advanceTime(state, 1)
    return mapOf("text" to text, "turn_cost" to 1, "hypothesis_id" to hyp.id)
}

// ------------------------------------------------------------------ 컴퓨터
private fun hComputerSearch(state: GameState, payload: Payload): Result {
    val threadId = payload["thread"] as? String ?: "night_light"
    val spec = COMPUTER_SEARCHES[threadId] ?: throw GameError("그 주제로는 아직 검색할 게 마땅치 않아.", 400)
    if (spec.requiresObservation && thread(state, threadId).observationCount == 0) {
        throw GameError("직접 본 게 있어야 뭘 검색할지 떠오를 것 같아.", 400)
    }

    state.searches.add(mapOf("query" to spec.query, "day" to state.day, "period" to state.period))
    val added = mutableListOf<String>()
    for (cand in spec.candidates) {
        state.infos.add(InfoEntry(id = newId(), thread = threadId, day = state.day, source = "computer", text = cand))
        added.add(cand)
    }
    val text = "'${spec.query}'(으)로 검색했다. 여러 후보가 나왔다."
    log(state, text)
    advanceTime(state, 1)
    return mapOf("text" to text, "turn_cost" to 1, "candidates" to added)
}

// ------------------------------------------------------------------ 책장
private fun hBookshelfSkim(state: GameState, payload: Payload): Result {
    val text = if (checkZeroTurn(state, "bookshelf")) ENOUGH_ALREADY.getValue("bookshelf") else BOOKSHELF_GLANCE
    log(state, text)
    return mapOf("text" to text, "turn_cost" to 0)
}

private fun hBookshelfRead(state: GameState, payload: Payload): Result {
    for ((threadId, book) in BOOKSHELF_UNLOCKED_BOOKS) {
        if (threadId in state.booksRead) continue
        val nInfos = state.infos.count { it.thread == threadId && it.source == "computer" }
        val unlocked = nInfos >= book.triggerMinInfos &&
            (book.triggerMinInfos > 0 || thread(state, threadId).observationCount > 0)
        if (unlocked) {
            state.booksRead.add(threadId)
            state.infos.add(InfoEntry(id = newId(), thread = threadId, day = state.day, source = "book", text = book.infoText))
            val text = book.text
            log(state, text)
            advanceTime(state, 1)
            return mapOf("text" to text, "turn_cost" to 1, "info_gained" to book.infoText)
        }
    }

    val text = BOOKSHELF_GENERIC_BOOKS.random()
    log(state, text)
    advanceTime(state, 1)
    return mapOf("text" to text, "turn_cost" to 1)
}

// ------------------------------------------------------------------ 보조 오브젝트
private fun hMirrorGlance(state: GameState, payload: Payload): Result {
    val text = if (checkZeroTurn(state, "mirror")) ENOUGH_ALREADY.getValue("mirror") else MIRROR_LINES.random()
    log(state, text)
    return mapOf("text" to text, "turn_cost" to 0)
}

private fun hBedLieDown(state: GameState, payload: Payload): Result {
    val text = BED_LINES.random()
    log(state, text)
    return mapOf("text" to text, "turn_cost" to 0)
}

private fun hRadioListen(state: GameState, payload: Payload): Result {
    val text = if (checkZeroTurn(state, "radio")) ENOUGH_ALREADY.getValue("radio") else RADIO_LINES.random().second
    log(state, text)
    return mapOf("text" to text, "turn_cost" to 0)
}

private fun hMailCheck(state: GameState, payload: Payload): Result {
    val text = if (state.day < 2) MAIL_LINES_BEFORE else MAIL_LINES_FLAVOR.random()
    log(state, text)
    return mapOf("text" to text, "turn_cost" to 0)
}

private fun hMemowallCheck(state: GameState, payload: Payload): Result {
    val text: String
    val questions: List<String>
    if (checkZeroTurn(state, "memowall")) {
        text = ENOUGH_ALREADY.getValue("memowall")
        questions = state.memoWall.values.toList()
    } else if (state.memoWall.isEmpty()) {
        text = "메모벽은 아직 비어 있다."
        questions = emptyList()
    } else {
        questions = state.memoWall.values.toList()
        text = "메모벽을 봤다. " + questions.joinToString(" / ")
    }
    log(state, text)
    return mapOf("text" to text, "turn_cost" to 0, "questions" to questions)
}

private fun hDoorExit(state: GameState, payload: Payload): Result {
    if (!state.exitUnlocked) {
        val text = "나가서 뭐 하지."
        log(state, text)
        return mapOf("text" to text, "turn_cost" to 0, "ended" to false)
    }
    state.ended = true
    val text = "문을 열고 밖으로 나섰다."
    log(state, text)
    return mapOf("text" to text, "turn_cost" to 0, "ended" to true, "end_label" to "Vertical Slice End")
}

private val HANDLERS: Map<Pair<String, String>, (GameState, Payload) -> Result> = mapOf(
    ("window" to "glance") to ::hWindowGlance,
    ("window" to "observe") to ::hWindowObserve,
    ("window" to "zoom") to ::hWindowZoom,
    ("boxes" to "organize") to ::hBoxesOrganize,
    ("desk" to "hypothesis") to ::hDeskHypothesis,
    ("computer" to "search") to ::hComputerSearch,
    ("bookshelf" to "skim") to ::hBookshelfSkim,
    ("bookshelf" to "read") to ::hBookshelfRead,
    ("mirror" to "glance") to ::hMirrorGlance,
    ("bed" to "lie_down") to ::hBedLieDown,
    ("radio" to "listen") to ::hRadioListen,
    ("mail" to "check") to ::hMailCheck,
    ("memowall" to "check") to ::hMemowallCheck,
    ("door" to "exit") to ::hDoorExit,
)

// ------------------------------------------------------------------ 화면용 스냅샷
fun snapshot(state: GameState): Map<String, Any?> = mapOf(
    "day" to state.day,
    "period" to state.period,
    "period_label" to (PERIOD_LABELS[state.period] ?: state.period),
    "map" to ROOM_MAP,
    "objects" to OBJECTS,
    "object_tiles" to OBJECT_TILES,
    "player_start" to listOf(PLAYER_START.first, PLAYER_START.second),
    "items" to state.items.toList(),
    "memo_wall" to state.memoWall.values.toList(),
    "window_resolution" to state.windowResolution,
    "window" to mapOf(
        "subjects" to WINDOW_SUBJECTS.map { mapOf("id" to it, "label" to windowSubjectLabel(state, it)) },
        "seen" to state.observationHistory.size,
        "total" to WINDOW_OBSERVATIONS.size,
        "knowledge_count" to state.knowledge.size,
    ),
    "exit_unlocked" to state.exitUnlocked,
    "ended" to state.ended,
    "log" to state.log.takeLast(30),
    "desk" to deskView(state),
    "hypothesis_templates" to HYPOTHESIS_TEMPLATES,
    "computer_searches" to COMPUTER_SEARCHES.mapValues { (_, spec) -> mapOf("query" to spec.query) },
)

data class DeskThreadView(
    val observations: MutableList<Map<String, Any?>> = mutableListOf(),
    val infos: MutableList<Map<String, Any?>> = mutableListOf(),
    var hypothesis: Map<String, Any?>? = null,
)

fun deskView(state: GameState): Map<String, DeskThreadView> {
    val threads = linkedMapOf<String, DeskThreadView>()
    for (obs in state.observations) {
        val v = threads.getOrPut(obs.thread) { DeskThreadView() }
        v.observations.add(mapOf("day" to obs.day, "period" to obs.period, "text" to obs.text))
    }
    for (info in state.infos) {
        val v = threads.getOrPut(info.thread) { DeskThreadView() }
        v.infos.add(mapOf("source" to info.source, "text" to info.text))
    }
    for (hyp in state.hypotheses) {
        val v = threads.getOrPut(hyp.thread) { DeskThreadView() }
        v.hypothesis = mapOf(
            "text" to hyp.latestText(),
            "status" to hyp.status,
            "history" to hyp.revisions.map { mapOf("day" to it.day, "period" to it.period, "status" to it.status, "text" to it.text, "reason" to it.reason) },
        )
    }
    return threads
}
