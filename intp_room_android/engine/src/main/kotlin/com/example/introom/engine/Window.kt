package com.example.introom.engine

import kotlin.random.Random

/*
 * 창문 관찰 엔진 (명세서 261011: WIN-01~06, 3장 문장 선정, REC-01/02, KNW-01/02).
 *
 * - 대상(바다/하늘/횟집/강아지/앞마당) 또는 "그냥 바라본다"를 고르면 문장 1개를 출력한다.
 *   턴 소비(1턴)와 시간대 갱신은 Engine.kt 의 advanceTime (기존 규칙)에 맡긴다.
 * - 후보 = 출현 조건 충족 ∩ 반복 허용. 가중치는 WEIGHTS, 이미 본 문장 ×0.7,
 *   최근 3회 이내 ×0.2, 직전 문장은 다른 후보가 충분하면 제외.
 * - 출력 결과로 관찰 이력(최초/최근/횟수), 사실 플래그, 지식(접함)을 남긴다. 같은 문장을 다시
 *   봐도 최초 발견이나 지식을 중복 생성하지 않고, 반복 열람으로 지식 단계가 오르지 않는다.
 *
 * Python 버전(app/engine/window.py)의 포트.
 */

const val WINDOW_ANY = "any"

private fun now(state: GameState) = TimePoint(state.day, state.period)

fun dogNameKnown(state: GameState): Boolean = state.npcRelations["dog"]?.get("name_known") == true

fun windowSubjectLabel(state: GameState, subject: String): String =
    if (subject == "dog" && dogNameKnown(state)) DOG_NAME else WINDOW_SUBJECT_LABELS.getValue(subject)

/** 이름을 알게 된 뒤에는 '강아지'를 '두식이'로 치환한다 (두 단어 모두 모음으로 끝나 조사가 그대로 맞는다). */
fun renderWindowText(state: GameState, text: String): String {
    if (!dogNameKnown(state)) return text
    return text.replace("강아지집", "$DOG_NAME 집").replace("강아지", DOG_NAME)
}

// ------------------------------------------------------------------ 조건 판정
private fun recordOk(state: GameState, key: String): Boolean {
    return when (val rule = RECORD_RULES.getValue(key)) {
        is RecordRule.FlagsMin -> rule.flags.count { state.factFlags[it] != null } >= rule.n
        is RecordRule.Days -> (state.factFlags[rule.flag]?.days?.size ?: 0) >= rule.n
        is RecordRule.Flag -> {
            val f = state.factFlags[rule.flag] ?: return false
            when (rule.kind) {
                "any" -> true
                "today" -> state.day in f.days
                "prev_day" -> f.days.any { it < state.day }
                "yesterday" -> (state.day - 1) in f.days
                "same_period_other_day" -> f.seenAt.any { it.day != state.day && it.period == state.period }
                else -> throw IllegalArgumentException("알 수 없는 기록 규칙: $rule")
            }
        }
    }
}

private fun knowledgeOk(state: GameState, key: String): Boolean {
    val rule = KNOWLEDGE_RULES.getValue(key)
    rule.any?.let { ids -> return ids.any { it in state.knowledge } }
    val all = rule.all.orEmpty()
    return all.isNotEmpty() && all.all { it in state.knowledge }
}

fun requirementsMet(state: GameState, obs: WindowObservation): Boolean {
    for (r in obs.requirements) {
        val ok = when (r.type) {
            "시간" -> state.period in TIME_RULES.getValue(r.key)
            "기록" -> recordOk(state, r.key)
            "지식" -> knowledgeOk(state, r.key)
            else -> false // 경험/질문: 1차 범위에 해당 행동·기록이 없다
        }
        if (!ok) return false
    }
    return true
}

/** 간격 반복 문장은 같은 날 다시 나오지 않는다 (쿨다운 길이는 플레이테스트로 조정). */
fun repeatAllowed(state: GameState, obs: WindowObservation): Boolean {
    if (obs.repeatPolicy != "interval") return true
    val h = state.observationHistory[obs.id] ?: return true
    return h.lastSeen.day != state.day
}

internal fun windowWeight(state: GameState, obs: WindowObservation): Double {
    var w = WEIGHTS.getValue(obs.weightClass)
    if (obs.id in state.observationHistory) w *= SEEN_MULT
    if (obs.id in state.windowRecent.takeLast(RECENT_WINDOW)) w *= RECENT_MULT
    return w
}

fun windowCandidates(state: GameState, subject: String): List<WindowObservation> {
    var cands = WINDOW_OBSERVATIONS.values.filter {
        it.subject == subject && requirementsMet(state, it) && repeatAllowed(state, it)
    }
    val last = state.windowRecent.lastOrNull()
    if (last != null && cands.size >= 3) { // 직전 문장은 다른 후보가 충분할 때(2개 이상) 제외
        cands = cands.filter { it.id != last }
    }
    return cands
}

/** 후보가 없을 때: 그 대상의 조건 없는 일반 반복 문장(안전한 반복 문장)을 먼저 찾는다. */
private fun safeFallback(subject: String, rng: Random): WindowObservation? {
    val safe = WINDOW_OBSERVATIONS.values.filter {
        it.subject == subject && it.requirements.isEmpty() && it.repeatPolicy == "normal"
    }
    return if (safe.isEmpty()) null else safe.random(rng)
}

private fun <T> weightedChoice(items: List<T>, weights: List<Double>, rng: Random): T {
    var x = rng.nextDouble() * weights.sum()
    for (i in items.indices) {
        x -= weights[i]
        if (x < 0.0) return items[i]
    }
    return items.last()
}

/** (실제 관찰한 대상, 문장) 을 고른다. 문장이 null 이면 중립 문장을 써야 한다. */
fun pickWindowObservation(
    state: GameState,
    subject: String?,
    rng: Random = Random.Default,
): Pair<String?, WindowObservation?> {
    var target = subject
    if (target == null || target == WINDOW_ANY) {
        val pool = WINDOW_SUBJECTS.filter { windowCandidates(state, it).isNotEmpty() }
        if (pool.isEmpty()) return null to null
        target = pool.random(rng)
    }
    val cands = windowCandidates(state, target)
    if (cands.isEmpty()) return target to safeFallback(target, rng)
    return target to weightedChoice(cands, cands.map { windowWeight(state, it) }, rng)
}

// ------------------------------------------------------------------ 기록
fun recordFacts(state: GameState, flags: List<String>) {
    for (flag in flags) {
        val f = state.factFlags.getOrPut(flag) { FactFlag(first = now(state)) }
        if (state.day !in f.days) f.days.add(state.day)
        val pair = now(state)
        if (pair !in f.seenAt) {
            f.seenAt.add(pair)
            while (f.seenAt.size > 40) f.seenAt.removeAt(0)
        }
    }
}

/** 지식을 '접함'으로 기록한다. 이미 있으면 새 경로만 sources 에 추가하고 false (중복 획득 금지). */
fun recordKnowledge(state: GameState, knowledgeId: String, source: String): Boolean {
    val src = KnowledgeSource(source, state.day, state.period)
    val entry = state.knowledge[knowledgeId]
    if (entry == null) {
        state.knowledge[knowledgeId] = KnowledgeEntry(sources = mutableListOf(src))
        return true
    }
    if (entry.sources.none { it.id == source }) entry.sources.add(src)
    return false
}

data class ObservationRecordResult(val firstSeen: Boolean, val newKnowledge: List<String>)

/** REC-01: 최초·최근 관찰 시점과 횟수. 반환값의 firstSeen 으로 새 발견 여부를 알려준다. */
fun recordWindowObservation(state: GameState, obs: WindowObservation): ObservationRecordResult {
    val existing = state.observationHistory[obs.id]
    val h = existing ?: ObservationHistory(firstSeen = now(state), lastSeen = now(state)).also {
        state.observationHistory[obs.id] = it
    }
    h.lastSeen = now(state)
    h.count += 1
    state.windowRecent.add(obs.id)
    if (state.windowRecent.size > 10) state.windowRecent = state.windowRecent.takeLast(10).toMutableList()
    recordFacts(state, obs.effects)
    val newKnowledge = mutableListOf<String>()
    if (obs.knowledgeMode == "discover") {
        for (kid in obs.knowledgeIds) {
            if (recordKnowledge(state, kid, obs.id)) newKnowledge.add(kid)
        }
    }
    return ObservationRecordResult(firstSeen = existing == null, newKnowledge = newKnowledge)
}
