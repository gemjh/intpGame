package com.example.introom.engine

/*
 * 자동 연결 시스템 (자동연결시스템_개발명세서_261012).
 *
 * 창문 관찰로 문장 하나가 정해지고 기록된 뒤, 그 관찰을 트리거로 하는 연결 정의를 찾아
 * 선행 경험·상황·지식 단계·중복/발전 조건을 모두 확인한다. 충족되면 관찰 문장 뒤에 붙일 연결 문장을
 * 돌려주고 state.connections 에 기록한다.
 *
 * - 자동 발생(A안), 추가 턴 없음. 일반 관찰 선택 확률은 건드리지 않는다 (정해진 관찰이 나왔을 때만 판정).
 * - 선행 경험은 실제 플레이 기록(observationHistory / 기존 세계 이벤트 Observation / knowledge.sources)에서만
 *   확인하고, evidence 에도 그 ID 만 남긴다. 기록이 없으면 연결하지 않는다 (허위 경험 생성 금지).
 * - 같은 연결 ID·단계는 한 번만 출력한다. 다음 단계는 이전 단계가 기록된 뒤 새 증거가 있을 때만 출력한다.
 * - 연결은 가설·탐구·메모벽·지식 단계를 바꾸지 않는다.
 *
 * followUpIds 와의 관계: followUpIds 는 "이 문장 다음에 나올 수 있는 관찰" 후보 관계(콘텐츠 그래프)일 뿐이고
 * 여기서 판정에 쓰지 않는다. 사고 연결은 아래 CONNECTIONS 에 트리거·선행 조건을 명시한 것만 발생한다.
 *
 * Python 버전(app/engine/connection.py)의 포트.
 */

val KNOWLEDGE_RANK = mapOf("encountered" to 1, "understood" to 2, "applied" to 3)

sealed class Prerequisite {
    /** 이번 행동 이전에 그 관찰 문장 중 하나를 실제로 봤음 */
    data class Obs(val ids: List<String>) : Prerequisite()
    /** 기존 세계 이벤트 관찰(Observation)이 그 탐구에 실제로 있음 */
    data class Thread(val thread: String) : Prerequisite()
    /** 지식을 그 단계 이상으로 갖고 있음 (encountered ≠ understood) */
    data class Knowledge(val id: String, val min: String) : Prerequisite()
}

data class ConnectionDef(
    val id: String,
    val case: String,
    val stage: Int,
    val trigger: List<String>,
    val prerequisites: List<Prerequisite>,
    val text: String,
    val periods: Set<String>? = null,
)

// 같은 id·stage 를 여러 정의가 공유할 수 있다 (예: C01 은 순서가 반대인 두 경로).
val CONNECTIONS: List<ConnectionDef> = listOf(
    // C01 바람과 파도 — 비교·의심. 너울이 원인이라고 확정하지 않는다.
    ConnectionDef(
        "sea_wind_wave_contrast_v1", "C01", 1, listOf("SEA-007"), listOf(Prerequisite.Obs(listOf("SEA-009"))),
        "전에는 바람이 거의 없는데도 파도가 계속 들어왔다. 오늘은 바람이 부는데도 잔잔하다. " +
            "지금 여기서 부는 바람만으로 파도를 설명할 수는 없겠다.",
    ),
    ConnectionDef(
        "sea_wind_wave_contrast_v1", "C01", 1, listOf("SEA-009"), listOf(Prerequisite.Obs(listOf("SEA-007"))),
        "전에는 바람이 부는데도 파도가 잔잔했다. 바람과 파도가 꼭 같이 움직이는 건 아닌 모양이다.",
    ),
    ConnectionDef(
        "sea_wind_wave_contrast_v1", "C01", 2, listOf("SEA-008"), emptyList(),
        "바람이 없는데 들어오던 파도, 바람이 부는데 잔잔하던 날. 그리고 지금은 파도가 몇 개씩 묶여서 온다. " +
            "파도가 여기 바람 말고 다른 무언가에 따라 움직이는 걸지도 모른다. 아직은 짐작이다.",
    ),
    // C02 개미와 강아지 — 재해석. 개미 페로몬 정보를 실제로 접했을 때만.
    ConnectionDef(
        "ant_dog_scent_v1", "C02", 1, listOf("DOG-004", "DOG-014", "DOG-018"),
        listOf(Prerequisite.Knowledge("ant_pheromone", "encountered")),
        "개미도 냄새로 길을 표시한다고 했지. 쓰는 방식은 다르겠지만, " +
            "둘 다 냄새에서 정보를 얻는다는 점은 비슷하다.",
    ),
    // C03 소녀와 강아지 — 회상·관계. 이름·관계는 단정하지 않는다.
    ConnectionDef(
        "girl_dog_familiar_v1", "C03", 1, listOf("DOG-023"),
        listOf(Prerequisite.Obs(listOf("SHOP-012", "SHOP-027", "SHOP-032", "DOG-026"))),
        "소녀가 곁에 있을 때 강아지가 반응하는 걸 전에도 본 적이 있다. 서로 익숙한 사이인 모양이다.",
    ),
    // C04 밤바다 불빛과 부표 — 지식 확장. 부표와 불빛을 같은 물체로 확정하지 않는다.
    ConnectionDef(
        "night_light_buoy_v1", "C04", 1, listOf("SEA-031", "SEA-032"),
        listOf(Prerequisite.Thread("night_light"), Prerequisite.Knowledge("navigation_marks", "encountered")),
        "저 부표를 보니 밤바다에서 깜빡이던 불빛이 떠오른다. 같은 물체인지는 모르겠지만, " +
            "저런 표지에도 불빛이 달려 있을 수 있겠지.",
        periods = TIME_RULES.getValue("낮"),
    ),
)

/** T10: 실제 콘텐츠에 없는 관찰/지식/탐구 ID 를 참조하는 정의는 명시적으로 실패시킨다. */
fun validateConnections(defs: List<ConnectionDef>) {
    val knownKnowledge = WINDOW_OBSERVATIONS.values.flatMap { it.knowledgeIds }.toSet()
    for (d in defs) {
        for (oid in d.trigger) require(oid in WINDOW_OBSERVATIONS) { "${d.id}: 트리거 관찰 $oid 없음" }
        for (p in d.prerequisites) when (p) {
            is Prerequisite.Obs -> p.ids.forEach { require(it in WINDOW_OBSERVATIONS) { "${d.id}: 선행 관찰 $it 없음" } }
            is Prerequisite.Thread ->
                require(p.thread in setOf("night_light", "moon", "restaurant")) { "${d.id}: 탐구 ${p.thread} 없음" }
            is Prerequisite.Knowledge -> {
                require(p.id in knownKnowledge) { "${d.id}: 지식 ${p.id} 공급원 없음" }
                require(p.min in KNOWLEDGE_RANK) { "${d.id}: 지식 단계 ${p.min}" }
            }
        }
    }
    for ((cid, ds) in defs.groupBy { it.id }) {
        val stages = ds.map { it.stage }.toSet()
        require(stages == (1..stages.max()).toSet()) { "$cid: 단계가 1부터 연속이 아님 ${stages.sorted()}" }
    }
}

// 이 파일의 함수를 처음 부를 때(클래스 초기화) 정의를 검증한다.
private val validated = validateConnections(CONNECTIONS)

/** 선행 조건 하나를 실제 기록으로 확인하고, 근거가 된 기록 ID 를 돌려준다 (없으면 null). */
private fun evidenceFor(state: GameState, p: Prerequisite, currentId: String): String? = when (p) {
    // 이번 행동으로 막 기록된 문장은 '이전 경험'이 아니다.
    is Prerequisite.Obs -> p.ids.firstOrNull { oid ->
        val h = state.observationHistory[oid]
        h != null && (oid != currentId || h.count >= 2)
    }
    is Prerequisite.Thread -> state.observations.firstOrNull { it.thread == p.thread }?.id
    is Prerequisite.Knowledge -> {
        val entry = state.knowledge[p.id]
        if (entry == null || (KNOWLEDGE_RANK[entry.status] ?: 0) < KNOWLEDGE_RANK.getValue(p.min)) null
        else entry.sources.firstOrNull()?.id
    }
}

data class ConnectionHit(val id: String, val stage: Int, val text: String)

/** 방금 기록된 관찰 obs 로 발생하는 연결을 판정·기록한다. 한 관찰에 연결은 최대 하나. */
fun checkConnection(state: GameState, obs: WindowObservation, defs: List<ConnectionDef> = CONNECTIONS): ConnectionHit? {
    for (d in defs) {
        if (obs.id !in d.trigger) continue
        if ((state.connections[d.id]?.stage ?: 0) != d.stage - 1) continue // 이미 출력한 단계거나, 앞 단계가 아직 없다
        if (d.periods != null && state.period !in d.periods) continue
        val evidence = mutableListOf<String>()
        val ok = d.prerequisites.all { p ->
            val e = evidenceFor(state, p, obs.id) ?: return@all false
            if (e !in evidence) evidence.add(e)
            true
        }
        if (!ok) continue
        if (obs.id !in evidence) evidence.add(obs.id)
        recordConnection(state, d, obs.id, evidence)
        return ConnectionHit(d.id, d.stage, d.text)
    }
    return null
}

private fun recordConnection(state: GameState, d: ConnectionDef, triggerId: String, evidence: List<String>) {
    val rec = state.connections.getOrPut(d.id) { ConnectionRecord(firstDay = state.day) }
    rec.stage = d.stage
    rec.lastDay = state.day
    rec.triggerObservationId = triggerId
    rec.evidence = evidence.toMutableList()
    rec.history.add(ConnectionStageRecord(d.stage, state.day, state.period, triggerId, evidence.toList()))
}
