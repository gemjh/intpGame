package com.example.introom.engine

import java.util.UUID
import kotlin.math.floor

/*
 * 2차 범위: 준비된 질문(QST-01, REC-04), 지식 단계(KNW-02), 파도 주기 서사형 탐구(5장), 기록 화면(REC-03).
 *
 * - 탐구는 순서를 강제하지 않는다. 세기·기준점 측정·해석은 각각 독립 이력(actionsDone)으로 남고,
 *   질문 등록 없이도, 책(지식)을 먼저 읽어도, 며칠 뒤 이어서 해도 동작한다.
 * - 질문은 관찰·행동으로 "떠오르고", 붙잡을지는 플레이어가 고른다. 완료/미완료 상태는 없다.
 * - 지식 단계는 행동으로만 오른다: 해석하면 이해함, 다른 날 다시 재서 비교하면 적용함. 반복 열람으로는 오르지 않는다.
 * - 해석은 기록이 뒷받침하는 정도(추측/가능성/유력)만 남기고 정답을 확정하지 않는다.
 * - 이 탐구는 메모벽·문 해금(ThreadState)에 관여하지 않는다 (엔딩 변경은 별도 합의 사항).
 *
 * Python 버전(app/engine/inquiry.py + inquiry_content.py)의 포트. 문장은 전부 초안(콘텐츠 승인 필요)이다.
 */

// ------------------------------------------------------------------ 콘텐츠 (초안)
data class PreparedQuestion(val text: String, val sources: List<String>)

val QUESTIONS: Map<String, PreparedQuestion> = linkedMapOf(
    "wave_interval_constant" to PreparedQuestion(
        "파도 간격은 정말 일정할까?", listOf("SEA-004", "SEA-008", "act:wave_count", "act:wave_measure"),
    ),
    "tide_vs_wave" to PreparedQuestion("물이 들어오는 것과 파도가 밀려오는 건 다른 걸까?", listOf("SEA-018")),
)
val QUESTION_STATES = listOf("arisen", "held", "revisited")
val QUESTION_STATE_LABELS = linkedMapOf("arisen" to "떠오름", "held" to "붙잡음", "revisited" to "다시 생각함")

const val WAVE_THREAD = "wave_period"
private const val WAVE_BOOK_FACT = "sea_wave"
private const val WAVE_BOOK_TEXT = "파도에 관한 얇은 책이 눈에 들어왔다. 예전엔 그냥 지나쳤던 책이다."
private const val WAVE_BOOK_INFO = "같은 자리를 지나는 파도와 그다음 파도 사이의 시간을 주기라고 한다. " +
    "한 번만 재지 말고 여러 번을 재서 나누면 더 믿을 만하다고 적혀 있다."

val KNOWLEDGE_STATUS_LABELS = mapOf("encountered" to "접함", "understood" to "이해함", "applied" to "적용함")
private val EXTRA_KNOWLEDGE_LABELS = mapOf("wave_period" to "파동의 주기")

private val WAVE_DARK_PERIODS = setOf("evening", "night")
private const val WAVE_DARK_TEXT = "밖이 어두워서 파도가 잘 보이지 않는다. 지금은 셀 수가 없겠다."

val WAVE_INTERPRETATIONS = listOf(
    "파도 간격은 거의 일정하다.",
    "파도 간격은 날마다 달라진다.",
    "아직 판단하기엔 기록이 부족하다.",
)
private val WAVE_VERDICTS: Map<String, List<Pair<String, String>>> = mapOf(
    "rough_only" to listOf(
        "guess" to "어림으로 센 것뿐이라 비교할 근거가 약하다.",
        "guess" to "어림으로 센 것뿐이라 비교할 근거가 약하다.",
        "possible" to "어림으로 센 숫자만으로는 정말 부족하다. 제대로 재 봐야겠다.",
    ),
    "one_day" to listOf(
        "guess" to "하루치 기록만으로는 날마다 어떤지 말할 수 없다.",
        "guess" to "하루치 기록만으로는 날마다 어떤지 말할 수 없다.",
        "likely" to "하루치 기록뿐이다. 다른 날에도 재 봐야 비교할 수 있다.",
    ),
    "days_differ" to listOf(
        "guess" to "그런데 날짜별 기록은 꽤 차이가 난다. 지금 기록과는 잘 맞지 않는다.",
        "likely" to "날짜별 기록이 실제로 다르다. 다만 왜 달라지는지는 아직 모른다.",
        "possible" to "날짜별로 차이가 보이긴 한다. 며칠 더 재 보면 더 분명해지겠지.",
    ),
    "days_similar" to listOf(
        "possible" to "지금까지는 날짜가 달라도 비슷했다. 다른 날씨에서도 그런지는 모르겠다.",
        "guess" to "그런데 지금까지 잰 날들은 서로 비슷했다.",
        "possible" to "지금까지는 비슷하다. 며칠 더 재 보면 더 분명해지겠지.",
    ),
)
private const val WAVE_DIFFER_THRESHOLD = 1.0

private fun now(state: GameState) = TimePoint(state.day, state.period)

// ------------------------------------------------------------------ 질문
/** source(창문 문장 ID 또는 act:<행동>)로 떠오르는 질문을 기록하고, 이번에 처음 떠오른 것만 돌려준다. */
fun raiseQuestions(state: GameState, source: String): List<Map<String, String>> {
    val new = mutableListOf<Map<String, String>>()
    for ((qid, q) in QUESTIONS) {
        if (source in q.sources && qid !in state.questions) {
            state.questions[qid] = QuestionRecord(
                "arisen", now(state), source, mutableListOf(QuestionStateChange("arisen", state.day, state.period)),
            )
            new.add(mapOf("id" to qid, "text" to q.text))
        }
    }
    return new
}

/** QST-01/REC-04: 떠오른 질문만 붙잡거나(held), 다시 생각하거나(revisited), 내려놓을(arisen) 수 있다. 0턴. */
fun setQuestionState(state: GameState, qid: String?, newState: String?): Map<String, Any?> {
    val q = state.questions[qid] ?: throw GameError("아직 떠오르지 않은 질문이다.", 400)
    if (newState !in QUESTION_STATES) throw GameError("알 수 없는 질문 상태다.", 400)
    if (q.state != newState) {
        q.state = newState!!
        q.history.add(QuestionStateChange(newState, state.day, state.period))
    }
    return mapOf("id" to qid, "state" to newState, "label" to QUESTION_STATE_LABELS[newState], "turn_cost" to 0)
}

// ------------------------------------------------------------------ 지식
/** 행동으로 지식 단계를 올린다. 이미 그 단계 이상이면 그대로 (단계를 낮추지 않는다). */
fun raiseKnowledge(state: GameState, kid: String, toStatus: String, source: String): Boolean {
    val entry = state.knowledge[kid] ?: return false
    if ((KNOWLEDGE_RANK[entry.status] ?: 0) >= KNOWLEDGE_RANK.getValue(toStatus)) return false
    entry.status = toStatus
    entry.sources.add(KnowledgeSource(source, state.day, state.period))
    return true
}

/** 책장에서 파도 책이 눈에 들어오면 읽는다 (지식 '접함'). 조건이 안 되면 null. */
fun readWaveBook(state: GameState): Map<String, Any?>? {
    if (WAVE_THREAD in state.booksRead || WAVE_BOOK_FACT !in state.factFlags) return null
    state.booksRead.add(WAVE_THREAD)
    state.infos.add(InfoEntry(UUID.randomUUID().toString().replace("-", "").take(10), WAVE_THREAD, state.day, "book", WAVE_BOOK_INFO))
    recordKnowledge(state, "wave_period", "book:$WAVE_THREAD")
    return mapOf("text" to WAVE_BOOK_TEXT, "info_gained" to WAVE_BOOK_INFO, "knowledge" to "wave_period")
}

// ------------------------------------------------------------------ 파도 주기 탐구
private fun action(state: GameState, id: String) = state.actionsDone.getOrPut(id) { ActionRecord() }

/** 파도를 한 번이라도 눈여겨본 뒤에 세기/측정 행동이 보인다 (질문 등록은 필요 없다). */
fun waveAvailable(state: GameState) = "sea_wave" in state.factFlags || "wave_interval" in state.factFlags

private fun fmt(sec: Double): String = if (sec % 1.0 == 0.0) sec.toInt().toString() else sec.toString()

/** 세기: 시계 없이 어림으로 센다. 경험 '측정'으로는 치지 않는다. */
fun waveCount(state: GameState): MutableMap<String, Any?> {
    if (state.period in WAVE_DARK_PERIODS) return mutableMapOf("text" to WAVE_DARK_TEXT, "turn_cost" to 0)
    val reading = waveReading(state.day, state.period, state.seed, "count")
    val lo = maxOf(2, floor(reading).toInt() - 2)
    val hi = lo + 4
    val rec = action(state, "wave_count")
    rec.count += 1
    rec.history.add(ActionEntry(state.day, state.period, low = lo, high = hi))
    recordFacts(state, listOf("wave_interval", "sea_wave", "subject:sea"))
    return mutableMapOf(
        "text" to "파도가 들어올 때마다 속으로 하나, 둘 세어 봤다. 대충 ${lo}초에서 ${hi}초쯤 되는 것 같다. " +
            "세는 속도가 일정한지부터 자신이 없다.",
        "turn_cost" to 1,
        "questions" to raiseQuestions(state, "act:wave_count"),
    )
}

/** 기준점 재측정: 스톱워치로 열 번을 재서 나눈다. 경험 '파도_주기_측정'이 된다. */
fun waveMeasure(state: GameState): MutableMap<String, Any?> {
    if (state.period in WAVE_DARK_PERIODS) return mutableMapOf("text" to WAVE_DARK_TEXT, "turn_cost" to 0)
    val sec = waveReading(state.day, state.period, state.seed, "measure")
    val rec = action(state, "wave_measure")
    val prev = rec.history.lastOrNull()
    val prevOtherDay = rec.history.lastOrNull { it.day != state.day }
    var text = "바위 하나를 기준점으로 정했다. 파도가 바위에 부딪힐 때마다 휴대폰 스톱워치를 눌렀다. " +
        "열 번 사이를 재서 나누니 약 ${fmt(sec)}초."
    if (prev != null && prev.day == state.day) {
        text += " 아까는 약 ${fmt(prev.seconds!!)}초였다."
    } else if (prevOtherDay != null) {
        text += " 지난번(${prevOtherDay.day}일차)에는 약 ${fmt(prevOtherDay.seconds!!)}초였다."
    }
    rec.count += 1
    rec.history.add(ActionEntry(state.day, state.period, seconds = sec))
    recordFacts(state, listOf("wave_interval", "sea_wave", "subject:sea"))
    var applied = false
    // 지식을 가진 채 다른 날의 기록과 비교하면 '적용함' (접함/이해함 어느 쪽에서든 — 순서를 강제하지 않는다).
    if (prevOtherDay != null && "wave_period" in state.knowledge) {
        applied = raiseKnowledge(state, "wave_period", "applied", "act:wave_measure")
        if (applied) text += " 책에서 본 '주기'로 정리해 두니 날짜가 달라도 기록끼리 비교하기가 편하다."
    }
    return mutableMapOf(
        "text" to text, "turn_cost" to 1, "seconds" to sec, "knowledge_applied" to applied,
        "questions" to raiseQuestions(state, "act:wave_measure"),
    )
}

private fun waveBasis(state: GameState): Pair<String, WaveBasis> {
    val perDay = state.actionsDone["wave_measure"]?.history.orEmpty().groupBy({ it.day }, { it.seconds!! })
    val means = perDay.mapValues { (_, v) -> v.average() }
    val rough = state.actionsDone["wave_count"]?.count ?: 0
    if (means.isEmpty()) return "rough_only" to WaveBasis(emptyList(), rough)
    if (means.size == 1) return "one_day" to WaveBasis(means.keys.sorted(), rough)
    val spread = means.values.max() - means.values.min()
    val basis = WaveBasis(means.keys.sorted(), rough, Math.round(spread * 100) / 100.0)
    return (if (spread >= WAVE_DIFFER_THRESHOLD) "days_differ" else "days_similar") to basis
}

fun canInterpret(state: GameState) =
    (state.actionsDone["wave_count"]?.count ?: 0) > 0 || (state.actionsDone["wave_measure"]?.count ?: 0) > 0

/** 해석/검증: 플레이어가 고른 해석을 기록과 대조해 상태를 남긴다. 가설로 저장되고, 틀려도 지우지 않는다. */
fun waveInterpret(state: GameState, choice: Int?): MutableMap<String, Any?> {
    if (!canInterpret(state)) throw GameError("아직 파도를 세거나 재 본 기록이 없다.", 400)
    if (choice == null || choice !in WAVE_INTERPRETATIONS.indices) throw GameError("고를 수 있는 해석이 아니다.", 400)
    val (situation, basis) = waveBasis(state)
    val (status, note) = WAVE_VERDICTS.getValue(situation)[choice]
    val claim = WAVE_INTERPRETATIONS[choice]
    var text = "책상에서 파도 기록을 놓고 생각해 봤다. “$claim” $note"

    val rev = HypothesisRevision(state.day, state.period, status, claim, note)
    val hyp = state.hypotheses.firstOrNull { it.thread == WAVE_THREAD }
    if (hyp == null) {
        state.hypotheses.add(
            Hypothesis(UUID.randomUUID().toString().replace("-", "").take(10), WAVE_THREAD, state.day, claim, status, mutableListOf(rev)),
        )
    } else {
        hyp.revisions.add(rev)
        hyp.status = status
    }
    val rec = action(state, "wave_interpret")
    rec.count += 1
    rec.history.add(ActionEntry(state.day, state.period, choice = choice, status = status, basis = basis))

    var understood = false
    if (basis.measuredDays.isNotEmpty()) { // 실제로 잰 기록이 있어야 '주기'라는 개념과 연결된다
        understood = raiseKnowledge(state, "wave_period", "understood", "act:wave_interpret")
        if (understood) text += " 책에서 본 '주기'가 이 숫자를 말하는 거였구나."
    }
    return mutableMapOf("text" to text, "turn_cost" to 1, "status" to status, "knowledge_understood" to understood)
}

// ------------------------------------------------------------------ 기록 화면 (REC-03, 0턴)
data class RecordLine(val text: String, val questionId: String? = null, val questionState: String? = null)

data class RecordsView(
    val observations: List<RecordLine>,
    val questions: List<RecordLine>,
    val knowledge: List<RecordLine>,
    val connections: List<RecordLine>,
)

fun knowledgeLabel(kid: String): String = KNOWLEDGE_LABELS[kid] ?: EXTRA_KNOWLEDGE_LABELS[kid] ?: kid

private fun sourceLabel(sid: String): String = when {
    sid.startsWith("book:") -> "책"
    sid == "act:wave_measure" -> "파도 측정"
    sid == "act:wave_interpret" -> "파도 기록 해석"
    sid == "act:wave_count" -> "파도 세기"
    sid.startsWith("act:") -> "탐구"
    sid in WINDOW_OBSERVATIONS -> "창밖 관찰 $sid"
    else -> sid
}

private fun connectionText(cid: String, stage: Int, trigger: String): String =
    CONNECTIONS.firstOrNull { it.id == cid && it.stage == stage && trigger in it.trigger }?.text ?: ""

private fun whenText(day: Int, period: String) = "${day}일차 ${PERIOD_LABELS[period] ?: period}"

fun recordsView(state: GameState): RecordsView {
    data class Row(val day: Int, val period: String, val text: String)
    val rows = mutableListOf<Row>()
    for ((oid, h) in state.observationHistory) {
        val count = if (h.count > 1) " (${h.count}번 봄)" else ""
        rows.add(Row(h.firstSeen.day, h.firstSeen.period,
            "· ${whenText(h.firstSeen.day, h.firstSeen.period)}$count — ${renderWindowText(state, WINDOW_OBSERVATIONS.getValue(oid).text)}"))
    }
    for (o in state.observations) rows.add(Row(o.day, o.period, "· ${whenText(o.day, o.period)} — ${o.text}"))
    for (h in state.actionsDone["wave_count"]?.history.orEmpty()) {
        rows.add(Row(h.day, h.period, "· ${whenText(h.day, h.period)} — [파도 세기] ${h.low}~${h.high}초쯤"))
    }
    for (h in state.actionsDone["wave_measure"]?.history.orEmpty()) {
        rows.add(Row(h.day, h.period, "· ${whenText(h.day, h.period)} — [파도 측정] 약 ${fmt(h.seconds!!)}초"))
    }
    val observations = rows.sortedWith(compareBy({ it.day }, { PERIODS.indexOf(it.period) })).map { RecordLine(it.text) }

    val questions = state.questions.filterKeys { it in QUESTIONS }.map { (qid, q) ->
        RecordLine("${QUESTIONS.getValue(qid).text}  [${QUESTION_STATE_LABELS[q.state]}]", qid, q.state)
    }
    val knowledge = state.knowledge.map { (kid, k) ->
        RecordLine("· ${knowledgeLabel(kid)} [${KNOWLEDGE_STATUS_LABELS[k.status] ?: k.status}] — " +
            k.sources.joinToString(", ") { sourceLabel(it.id) })
    }
    val connections = state.connections.flatMap { (cid, rec) ->
        rec.history.map { h -> RecordLine("· ${h.day}일차 — ${renderWindowText(state, connectionText(cid, h.stage, h.triggerObservationId))}") }
    }
    return RecordsView(observations, questions, knowledge, connections)
}
