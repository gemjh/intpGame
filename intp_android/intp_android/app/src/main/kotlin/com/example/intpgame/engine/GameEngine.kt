package com.example.intpgame.engine

import java.util.Locale
import kotlin.math.max
import kotlin.random.Random

/**
 * 게임 규칙: 행동 실행 -> 이벤트 조건 검사 -> 이벤트 선택 -> 시간 경과.
 * 파이썬 버전(app/engine.py)과 같은 규칙/순서를 따른다.
 *
 * 흐름의 핵심: 이벤트가 발생하면 선택이 끝날 때까지 시간 경과를 보류(pendingAdvance)하고,
 * 선택을 마치면 그때 한꺼번에 흘려보낸다.
 */
class GameEngine(
    val content: GameContent,
    private val rng: Random = Random.Default,
) {
    companion object {
        val PERIODS = listOf("morning", "afternoon", "evening", "night")
        const val MAX_LOGS = 200
        const val MOVE_ENERGY_COST = 5
        const val BASE_NEW_DAY_RECOVERY = 30       // 밤이 지나 자연스럽게 하루가 넘어갈 때
        const val SLEPT_NEW_DAY_RECOVERY = 50      // 침대에서 '잠자기'로 넘어갈 때
        const val NEW_DAY_HYPERFOCUS_DECAY = 20
    }

    private val actionLocation: Map<String, String> = buildMap {
        for (loc in content.locations.values) for (obj in loc.objects) for (aid in obj.actions) put(aid, loc.id)
    }
    private val eventsById: Map<String, EventDef> = content.events.associateBy { it.id }

    // ------------------------------------------------------------------ 공개 API
    fun newGame(): GameState {
        val s = GameState()
        log(s, "낯선 동네의 낡은 원룸. 이삿짐 박스 사이로 창밖의 바다가 보인다. 새로운 생활이 시작됐다.")
        return s
    }

    fun executeAction(state: GameState, actionId: String): StepResult {
        if (state.activeEventId != null) throw GameError("진행 중인 이벤트부터 마무리해야 해.", 409)
        val action = content.actions[actionId] ?: throw GameError("알 수 없는 행동이야.", 404)
        val loc = actionLocation.getValue(actionId)
        if (state.currentLocation != loc || loc !in state.unlockedLocations) {
            throw GameError("지금 이 장소에서는 할 수 없는 행동이야.", 400)
        }
        val (ok, reason) = canAfford(state, action)
        if (!ok) throw GameError(reason, 400)

        val logsBefore = state.logs.size
        val changes = applyEffects(state, action.effects)
        state.actionCounts[actionId] = (state.actionCounts[actionId] ?: 0) + 1
        state.lastAction = actionId
        log(state, action.logs[rng.nextInt(action.logs.size)] + summarize(changes))

        if (action.skipToNextDay) {
            state.pendingAdvance = PERIODS.size - PERIODS.indexOf(state.period)
            state.pendingSlept = true
        } else {
            state.pendingAdvance = action.timeCost
            state.pendingSlept = false
        }
        return afterStep(state, changes, logsBefore)
    }

    fun move(state: GameState, locationId: String): StepResult {
        if (state.activeEventId != null) throw GameError("진행 중인 이벤트부터 마무리해야 해.", 409)
        val target = content.locations[locationId] ?: throw GameError("알 수 없는 장소야.", 404)
        if (locationId !in state.unlockedLocations) throw GameError("아직 갈 수 없는 장소야.", 400)
        if (locationId == state.currentLocation) throw GameError("이미 여기에 있어.", 400)
        if (state.energy < MOVE_ENERGY_COST) throw GameError("기력이 부족해서 움직일 수 없어.", 400)

        val logsBefore = state.logs.size
        val changes = applyEffects(state, Effects(status = mapOf("energy" to -MOVE_ENERGY_COST)))
        state.currentLocation = locationId
        state.lastAction = "move"
        log(state, "${target.name}(으)로 이동했다." + summarize(changes))
        state.pendingAdvance = 1
        state.pendingSlept = false
        return afterStep(state, changes, logsBefore)
    }

    fun chooseEvent(state: GameState, eventId: String, choiceIdx: Int): StepResult {
        val activeId = state.activeEventId
        if (activeId == null || activeId != eventId) throw GameError("지금은 해당 이벤트가 진행 중이 아니야.", 409)
        val event = eventsById.getValue(eventId)
        if (choiceIdx !in event.choices.indices) throw GameError("존재하지 않는 선택지야.", 400)
        val choice = event.choices[choiceIdx]

        val logsBefore = state.logs.size
        val changes = applyEffects(state, choice.effects)
        state.activeEventId = null
        log(state, "[${event.title}] ${choice.result}" + summarize(changes))
        state.pendingAdvance += choice.effects.advanceTime

        val newDay = flushTime(state)
        return StepResult(
            changes = changes,
            event = null,
            newDay = newDay,
            newLogs = state.logs.subList(logsBefore, state.logs.size).toList(),
            resultText = choice.result,
        )
    }

    // ------------------------------------------------------------------ 화면용 스냅샷
    fun snapshot(state: GameState): GameSnapshot {
        val labels = content.labels
        val loc = content.locations.getValue(state.currentLocation)
        val objects = loc.objects.map { obj ->
            ObjectView(
                id = obj.id,
                name = obj.name,
                icon = obj.icon,
                description = obj.description,
                actions = obj.actions.map { aid ->
                    val action = content.actions.getValue(aid)
                    val (ok, reason) = canAfford(state, action)
                    ActionView(
                        id = aid,
                        label = action.label,
                        hint = action.hint,
                        cost = costText(action),
                        enabled = ok && state.activeEventId == null,
                        reason = if (ok) "" else reason,
                    )
                },
            )
        }
        return GameSnapshot(
            day = state.day,
            period = state.period,
            periodLabel = labels.periods[state.period] ?: state.period,
            energy = state.energy,
            hyperfocus = state.hyperfocus,
            money = state.money,
            food = state.food,
            stats = labels.stats.map { (k, name) -> LabeledValue(k, name, state.stats[k] ?: 0) },
            interests = state.interests.entries
                .filter { it.value > 0 }
                .sortedByDescending { it.value }
                .map { (k, v) -> LabeledValue(k, labels.interests[k] ?: k, v) },
            discoveredInfo = state.discoveredInfo.toList(),
            logs = state.logs.toList(),
            locationId = loc.id,
            locationName = loc.name,
            locationDescription = loc.description,
            objects = objects,
            locations = state.unlockedLocations.mapNotNull { id ->
                content.locations[id]?.let { LocationTab(it.id, it.name) }
            },
            activeEvent = state.activeEventId?.let { eventPayload(it) },
        )
    }

    fun eventPayload(eventId: String): EventPayload {
        val e = eventsById.getValue(eventId)
        return EventPayload(e.id, e.title, e.description, e.choices.map { it.text })
    }

    // ------------------------------------------------------------------ 내부: 진행 흐름
    /** 기본 처리 후: 이벤트 조건 검사 -> (있으면 대기) / (없으면 시간 경과). */
    private fun afterStep(state: GameState, changes: Changes, logsBefore: Int): StepResult {
        val event = findEvent(state)
        var payload: EventPayload? = null
        var newDay = false
        if (event != null) {
            state.activeEventId = event.id
            if (event.id !in state.seenEvents) state.seenEvents.add(event.id)
            state.eventLastDay[event.id] = state.day
            payload = eventPayload(event.id)
        } else {
            newDay = flushTime(state)
        }
        return StepResult(changes, payload, newDay, state.logs.subList(logsBefore, state.logs.size).toList())
    }

    private fun flushTime(state: GameState): Boolean {
        val n = state.pendingAdvance
        val slept = state.pendingSlept
        state.pendingAdvance = 0
        state.pendingSlept = false
        return if (n > 0) advanceTime(state, n, slept) else false
    }

    private fun advanceTime(state: GameState, periods: Int, slept: Boolean): Boolean {
        var newDay = false
        repeat(periods) {
            val idx = PERIODS.indexOf(state.period) + 1
            if (idx >= PERIODS.size) {
                state.period = PERIODS[0]
                state.day += 1
                onNewDay(state, slept)
                newDay = true
            } else {
                state.period = PERIODS[idx]
            }
        }
        return newDay
    }

    private fun onNewDay(state: GameState, slept: Boolean) {
        var recovery = if (slept) SLEPT_NEW_DAY_RECOVERY else BASE_NEW_DAY_RECOVERY
        val note: String
        if (state.food > 0) {
            state.status["food"] = state.food - 1
            note = "식량이 하나 줄었다"
        } else {
            recovery /= 2
            note = "배가 고파 잠을 설쳤다"
        }
        val before = state.energy
        state.status["energy"] = minOf(100, state.energy + recovery)
        state.status["hyperfocus"] = max(0, state.hyperfocus - NEW_DAY_HYPERFOCUS_DECAY)
        val gained = state.energy - before
        log(state, "── Day ${state.day} 아침이 밝았다. 기력 +$gained, $note. ──")
    }

    // ------------------------------------------------------------------ 내부: 이벤트 검사
    private fun findEvent(state: GameState): EventDef? {
        for (event in content.events) {
            if (event.once && event.id in state.seenEvents) continue
            val last = state.eventLastDay[event.id]
            if (event.cooldownDays > 0 && last != null && state.day - last < event.cooldownDays) continue
            if (conditionsMet(state, event.conditions)) return event
        }
        return null
    }

    private fun conditionsMet(state: GameState, c: Conditions): Boolean {
        c.lastAction?.let { if (state.lastAction !in it) return false }
        c.location?.let { if (state.currentLocation != it) return false }
        c.periodIn?.let { if (state.period !in it) return false }
        if (state.day < c.dayGte) return false
        for ((k, v) in c.actionCountsGte) if ((state.actionCounts[k] ?: 0) < v) return false
        for ((k, v) in c.interestsGte) if ((state.interests[k] ?: 0) < v) return false
        for ((k, v) in c.statsGte) if ((state.stats[k] ?: 0) < v) return false
        for ((k, v) in c.statusGte) if ((state.status[k] ?: 0) < v) return false
        for ((k, v) in c.statusLte) if ((state.status[k] ?: 0) > v) return false
        for (info in c.infoPresent) if (info !in state.discoveredInfo) return false
        for (info in c.infoAbsent) if (info in state.discoveredInfo) return false
        // 다른 조건이 모두 맞을 때만 확률을 굴린다.
        c.chance?.let { if (rng.nextDouble() >= it) return false }
        return true
    }

    // ------------------------------------------------------------------ 내부: 효과 적용
    private fun canAfford(state: GameState, action: ActionDef): Pair<Boolean, String> {
        for (key in listOf("energy", "money", "food")) {
            val delta = action.effects.status[key] ?: 0
            if (delta < 0 && (state.status[key] ?: 0) + delta < 0) {
                return false to "${content.labels.status[key] ?: key}이(가) 부족해."
            }
        }
        return true to ""
    }

    /** 효과를 적용하고 '실제로 변한 양'을 돌려준다 (클램프 반영). */
    private fun applyEffects(state: GameState, fx: Effects): Changes {
        val dStats = linkedMapOf<String, Int>()
        val dStatus = linkedMapOf<String, Int>()
        val dInterests = linkedMapOf<String, Int>()
        val unlocked = mutableListOf<String>()
        val discovered = mutableListOf<String>()

        for ((key, delta) in fx.stats) {
            val old = state.stats[key] ?: 0
            val new = max(0, old + delta)
            state.stats[key] = new
            if (new != old) dStats[key] = new - old
        }
        for ((key, delta) in fx.status) {
            val old = state.status[key] ?: 0
            val raw = old + delta
            val new = if (key == "energy" || key == "hyperfocus") raw.coerceIn(0, 100) else max(0, raw)
            state.status[key] = new
            if (new != old) dStatus[key] = new - old
        }
        for ((key, delta) in fx.interests) {
            val old = state.interests[key] ?: 0
            val new = max(0, old + delta)
            state.interests[key] = new
            if (new != old) dInterests[key] = new - old
        }
        for (loc in fx.unlockLocations) {
            if (loc !in state.unlockedLocations) {
                state.unlockedLocations.add(loc)
                unlocked.add(loc)
            }
        }
        for (info in fx.discover) {
            if (info !in state.discoveredInfo) {
                state.discoveredInfo.add(info)
                discovered.add(info)
            }
        }
        return Changes(dStats, dStatus, dInterests, unlocked, discovered)
    }

    // ------------------------------------------------------------------ 내부: 표시용 문자열
    private fun summarize(ch: Changes): String {
        val labels = content.labels
        val parts = mutableListOf<String>()
        fun add(names: Map<String, String>, m: Map<String, Int>, moneyAware: Boolean) {
            for ((key, delta) in m) {
                val sign = if (delta > 0) "+" else ""
                val shown = if (moneyAware && key == "money") String.format(Locale.US, "%,d", delta) else delta.toString()
                parts.add("${names[key] ?: key} $sign$shown")
            }
        }
        add(labels.status, ch.status, true)
        add(labels.stats, ch.stats, false)
        add(labels.interests, ch.interests, false)
        for (loc in ch.unlocked) parts.add("[해금] ${content.locations.getValue(loc).name}")
        for (info in ch.discovered) parts.add("[발견] $info")
        return if (parts.isEmpty()) "" else "  (${parts.joinToString(", ")})"
    }

    private fun costText(action: ActionDef): String {
        val parts = mutableListOf<String>()
        for (key in listOf("energy", "money", "food")) {
            val delta = action.effects.status[key] ?: 0
            if (delta != 0) {
                val shown = if (key == "money") String.format(Locale.US, "%+,d", delta) else String.format(Locale.US, "%+d", delta)
                parts.add("${content.labels.status[key] ?: key} $shown")
            }
        }
        if (action.skipToNextDay) parts.add("다음 날로")
        return parts.joinToString(" / ")
    }

    private fun log(state: GameState, text: String) {
        val label = content.labels.periods[state.period] ?: state.period
        state.logs.add("[D${state.day} $label] $text")
        while (state.logs.size > MAX_LOGS) state.logs.removeAt(0)
    }
}
