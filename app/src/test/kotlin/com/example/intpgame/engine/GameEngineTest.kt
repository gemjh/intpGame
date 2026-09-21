package com.example.intpgame.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import kotlin.random.Random

/**
 * 파이썬 버전(tests/test_engine.py)과 같은 시나리오를 Kotlin 엔진에 적용한 테스트.
 * 콘텐츠는 실제 앱이 쓰는 assets 폴더의 JSON 파일을 그대로 읽는다. (Gradle 유닛 테스트의 작업 폴더 = app/)
 */
class GameEngineTest {

    /** chance 판정을 강제하기 위한 난수 (nextDouble 고정, nextInt 는 항상 0). */
    private class FixedRandom(private val value: Double) : Random() {
        override fun nextBits(bitCount: Int): Int = 0
        override fun nextDouble(): Double = value
    }

    private fun loadContent(): GameContent {
        fun read(name: String) = File("src/main/assets/$name").readText(Charsets.UTF_8)
        return ContentLoader.load(read("objects.json"), read("actions.json"), read("events.json"))
    }

    private val content by lazy { loadContent() }

    private fun fresh(rng: Random = Random(0)): Pair<GameEngine, GameState> {
        val eng = GameEngine(content, rng)
        return eng to eng.newGame()
    }

    private fun assertGameError(code: Int? = null, block: () -> Unit) {
        try {
            block()
        } catch (e: GameError) {
            if (code != null) assertEquals(code, e.code)
            return
        }
        fail("GameError 가 발생해야 한다")
    }

    private fun lookThreeTimes(eng: GameEngine, s: GameState): StepResult {
        var r: StepResult? = null
        repeat(3) { r = eng.executeAction(s, "window_look") }
        return r!!
    }

    // ------------------------------------------------------------------ 콘텐츠
    @Test
    fun contentShape() {
        val (eng, _) = fresh()
        assertEquals(13, eng.content.actions.size)                      // 방 10개 + 해금 장소 3개
        val roomActions = eng.content.locations.getValue("room").objects.sumOf { it.actions.size }
        assertEquals(10, roomActions)
        assertEquals(6, eng.content.locations.getValue("room").objects.size)
        assertTrue(eng.content.events.size >= 5)
        assertEquals(
            listOf("thinking", "execution", "curiosity", "creativity", "social", "reality"),
            eng.content.labels.stats.keys.toList(),
        )
    }

    @Test
    fun newGameDefaults() {
        val (_, s) = fresh()
        assertEquals(1, s.day)
        assertEquals("morning", s.period)
        assertEquals(100, s.energy)
        assertEquals(50000, s.money)
        assertEquals(3, s.food)
        assertEquals(listOf("room"), s.unlockedLocations)
        assertEquals(10, s.stats["thinking"])
        assertEquals(3, s.stats["social"])
    }

    // ------------------------------------------------------------------ 행동
    @Test
    fun actionAppliesEffectsAndAdvancesTime() {
        val (eng, s) = fresh()
        val r = eng.executeAction(s, "read_book")
        assertEquals(12, s.stats["thinking"])
        assertEquals(11, s.stats["curiosity"])
        assertEquals(90, s.energy)
        assertEquals(1, s.interests["knowledge"])
        assertEquals(1, s.actionCounts["read_book"])
        assertEquals("afternoon", s.period)
        assertNull(r.event)
        assertFalse(r.newDay)
        assertTrue(r.newLogs.first().contains("사고력 +2"))
    }

    @Test
    fun unknownAndWrongLocationActionsRejected() {
        val (eng, s) = fresh()
        assertGameError(404) { eng.executeAction(s, "nope") }
        assertGameError(400) { eng.executeAction(s, "buy_food") }     // 편의점은 아직 못 감
    }

    @Test
    fun insufficientEnergyRejectedWithoutSideEffects() {
        val (eng, s) = fresh()
        s.status["energy"] = 5
        assertGameError(400) { eng.executeAction(s, "computer_code") }
        assertEquals(5, s.energy)
        assertEquals("morning", s.period)
        assertTrue(s.actionCounts.isEmpty())
    }

    @Test
    fun noFoodBlocksMeal() {
        val (eng, s) = fresh()
        s.status["food"] = 0
        assertGameError(400) { eng.executeAction(s, "eat_meal") }
    }

    @Test
    fun energyIsClamped() {
        val (eng, s) = fresh()
        s.status["energy"] = 95
        eng.executeAction(s, "lie_down")           // +10 -> 100 으로 잘림
        assertEquals(100, s.energy)
    }

    // ------------------------------------------------------------------ 시간 / 하루 넘김
    @Test
    fun timeCycleAndNewDay() {
        val (eng, s) = fresh(FixedRandom(0.99))     // 잡생각 이벤트가 안 터지게
        for (expected in listOf("afternoon", "evening", "night")) {
            eng.executeAction(s, "plan_day")
            assertEquals(expected, s.period)
        }
        val r = eng.executeAction(s, "plan_day")   // 밤 -> 다음 날 아침
        assertTrue(r.newDay)
        assertEquals(2, s.day)
        assertEquals("morning", s.period)
        assertEquals(2, s.food)                     // 하루 지나며 식량 -1
    }

    @Test
    fun sleepSkipsToNextDayWithBiggerRecovery() {
        val (eng, s) = fresh()
        s.status["energy"] = 20
        s.status["hyperfocus"] = 50
        val r = eng.executeAction(s, "sleep")
        assertTrue(r.newDay)
        assertEquals(2, s.day)
        assertEquals("morning", s.period)
        assertEquals(100, s.energy)                 // 20 +40(수면) +50(아침) -> 100 상한
        assertEquals(10, s.hyperfocus)              // -20(수면) -20(새 날)
    }

    @Test
    fun hungerHalvesRecovery() {
        val (eng, s) = fresh()
        s.status["food"] = 0
        s.status["energy"] = 10
        eng.executeAction(s, "sleep")               // +40 => 50, 굶었으니 회복 50/2=25
        assertEquals(75, s.energy)
        assertEquals(0, s.food)
    }

    // ------------------------------------------------------------------ 이벤트
    @Test
    fun fishRockEventTriggersOnThirdLookAndBlocksActions() {
        val (eng, s) = fresh()
        val r = lookThreeTimes(eng, s)
        assertEquals("evt_fish_rock", r.event?.id)
        assertEquals("evt_fish_rock", s.activeEventId)
        assertEquals("evening", s.period)                           // 이벤트가 끝날 때까지 시간 보류
        assertGameError(409) { eng.executeAction(s, "read_book") }
        assertGameError(409) { eng.chooseEvent(s, "evt_boxes_secret", 0) }
        assertEquals("evt_fish_rock", eng.snapshot(s).activeEvent?.id)
        assertTrue(eng.snapshot(s).objects.flatMap { it.actions }.none { it.enabled })
    }

    @Test
    fun fishRockChoiceUnlocksSeasideAndFlushesTime() {
        val (eng, s) = fresh()
        lookThreeTimes(eng, s)
        val r = eng.chooseEvent(s, "evt_fish_rock", 0)
        assertNull(s.activeEventId)
        assertEquals("night", s.period)                             // 대기 중이던 1칸 경과
        assertTrue("seaside_rock" in s.unlockedLocations)
        assertTrue("갯바위 위치 파악" in s.discoveredInfo)
        assertEquals(listOf("seaside_rock"), r.changes.unlocked)
        assertGameError(409) { eng.chooseEvent(s, "evt_fish_rock", 0) }
    }

    @Test
    fun fishRockRetriggersIfIgnored() {
        val (eng, s) = fresh(FixedRandom(0.99))
        lookThreeTimes(eng, s)
        eng.chooseEvent(s, "evt_fish_rock", 2)      // '계속 지켜본다' -> 해금 없음
        assertFalse("seaside_rock" in s.unlockedLocations)
        var guard = 0
        while (s.day < 2 && guard++ < 5) eng.executeAction(s, "plan_day")
        assertTrue(s.day >= 2)
        assertNull(s.activeEventId)
        val r = eng.executeAction(s, "window_look")
        assertEquals("evt_fish_rock", r.event?.id)
    }

    @Test
    fun chanceEventRespectsRng() {
        run {
            val (eng, s) = fresh(FixedRandom(0.99))
            assertNull(eng.executeAction(s, "lie_down").event)
        }
        run {
            val (eng, s) = fresh(FixedRandom(0.0))
            assertEquals("evt_rabbit_hole", eng.executeAction(s, "lie_down").event?.id)
        }
    }

    @Test
    fun eventChoiceAdvanceTimeAndBadIndex() {
        val (eng, s) = fresh(FixedRandom(0.0))
        eng.executeAction(s, "lie_down")
        assertGameError(400) { eng.chooseEvent(s, "evt_rabbit_hole", 9) }
        assertEquals("evt_rabbit_hole", s.activeEventId)            // 잘못된 선택은 상태를 바꾸지 않는다
        eng.chooseEvent(s, "evt_rabbit_hole", 0)                    // advance_time +1 -> 총 2칸
        assertEquals("evening", s.period)
        assertEquals(5 + 25, s.hyperfocus)
    }

    @Test
    fun onceEventOnlyFiresOnce() {
        val (eng, s) = fresh(FixedRandom(0.99))
        var r: StepResult? = null
        repeat(2) { r = eng.executeAction(s, "unpack_boxes") }
        assertEquals("evt_boxes_secret", r!!.event?.id)
        eng.chooseEvent(s, "evt_boxes_secret", 1)
        assertEquals(70000, s.money)
        if (s.energy < 15) s.status["energy"] = 100
        assertNull(eng.executeAction(s, "unpack_boxes").event)
    }

    @Test
    fun hungerEventLeadsToConvenienceStore() {
        val (eng, s) = fresh(FixedRandom(0.99))
        s.status["food"] = 2
        s.day = 2
        assertNull(eng.executeAction(s, "plan_day").event)          // 식량 2 -> 이벤트 없음
        s.status["food"] = 1
        val r = eng.executeAction(s, "plan_day")
        assertEquals("evt_hunger", r.event?.id)
        eng.chooseEvent(s, "evt_hunger", 0)
        assertTrue("convenience_store" in s.unlockedLocations)
        assertEquals(4, s.food)
        assertEquals(40000, s.money)
        assertEquals("night", s.period)                             // 이벤트 선택으로 2칸 경과 (낮 -> 밤)
        eng.move(s, "convenience_store")                            // 밤에 이동 -> 하루가 넘어가며 식량 -1
        assertEquals(3, s.day)
        assertEquals(3, s.food)
        eng.executeAction(s, "buy_food")
        assertEquals(6, s.food)
        assertEquals(30000, s.money)
        eng.executeAction(s, "chat_clerk")
        assertEquals(3 + 1 + 2, s.stats["social"])
    }

    @Test
    fun hyperfocusEvent() {
        val (eng, s) = fresh(FixedRandom(0.99))
        s.status["hyperfocus"] = 85
        val r = eng.executeAction(s, "write_notes")                 // +6 -> 91
        assertEquals("evt_hyperfocus_crash", r.event?.id)
        eng.chooseEvent(s, "evt_hyperfocus_crash", 1)
        assertEquals(51, s.hyperfocus)
    }

    // ------------------------------------------------------------------ 이동
    @Test
    fun moveRules() {
        val (eng, s) = fresh()
        assertGameError(400) { eng.move(s, "seaside_rock") }        // 잠김
        assertGameError(400) { eng.move(s, "room") }                // 이미 여기
        assertGameError(404) { eng.move(s, "mars") }
        s.unlockedLocations.add("seaside_rock")
        eng.move(s, "seaside_rock")
        assertEquals("seaside_rock", s.currentLocation)
        assertEquals(95, s.energy)
        assertEquals("afternoon", s.period)
        assertGameError(400) { eng.executeAction(s, "read_book") }  // 방 안 행동은 못 함
        eng.executeAction(s, "observe_tide")
        assertEquals(2, s.interests["fish"])
    }

    // ------------------------------------------------------------------ 스냅샷
    @Test
    fun snapshotMarksUnaffordableActionsDisabled() {
        val (eng, s) = fresh()
        s.status["energy"] = 8
        val snap = eng.snapshot(s)
        val acts = snap.objects.flatMap { it.actions }.associateBy { it.id }
        assertFalse(acts.getValue("computer_code").enabled)
        assertTrue(acts.getValue("computer_code").reason.contains("기력"))
        assertTrue(acts.getValue("sleep").enabled)
        assertEquals("기력 -10", acts.getValue("read_book").cost)
        assertEquals("기력 +40 / 다음 날로", acts.getValue("sleep").cost)
        assertEquals(listOf("사고력", "실행력", "호기심", "창의력", "사회성", "현실감각"), snap.stats.map { it.label })
        assertEquals("아침", snap.periodLabel)
    }

    // ------------------------------------------------------------------ 저장 코덱
    @Test
    fun stateCodecRoundTrip() {
        val (eng, s) = fresh(FixedRandom(0.99))
        lookThreeTimes(eng, s)                                       // 이벤트 진행 중인 상태도 저장/복원되어야 한다
        val json = StateCodec.encode(s)
        val loaded = StateCodec.decode(json, content)
        assertNotNull(loaded)
        assertEquals(json, StateCodec.encode(loaded!!))
        assertEquals("evt_fish_rock", loaded.activeEventId)
        assertEquals(1, loaded.pendingAdvance)
        // 복원한 상태로 이어서 플레이할 수 있다
        eng.chooseEvent(loaded, "evt_fish_rock", 0)
        assertTrue("seaside_rock" in loaded.unlockedLocations)
    }

    @Test
    fun stateCodecRejectsBrokenSaves() {
        assertNull(StateCodec.decode("{broken", content))
        assertNull(StateCodec.decode("[]", content))
        assertNull(StateCodec.decode("""{"period":"midnight"}""", content))
        assertNull(StateCodec.decode("""{"currentLocation":"mars","unlockedLocations":["mars"]}""", content))
        assertNull(StateCodec.decode("""{"activeEventId":"evt_nope"}""", content))
        // 필드가 일부 없는 옛 세이브는 기본값으로 채워 읽힌다
        val minimal = StateCodec.decode("""{"day":3}""", content)
        assertNotNull(minimal)
        assertEquals(3, minimal!!.day)
        assertEquals(100, minimal.energy)
    }

    @Test
    fun logCap() {
        val (eng, s) = fresh(FixedRandom(0.99))
        s.status["energy"] = 100
        repeat(300) {
            s.status["energy"] = 100
            s.status["food"] = 3
            s.status["hyperfocus"] = 0
            if (s.activeEventId != null) eng.chooseEvent(s, s.activeEventId!!, 2)
            else eng.executeAction(s, "plan_day")
        }
        assertEquals(GameEngine.MAX_LOGS, s.logs.size)
    }

    // ------------------------------------------------------------------ 무작위 플레이
    @Test
    fun randomPlaythroughNeverBreaksInvariants() {
        for (seed in 0 until 20) {
            val chooser = Random(seed)
            val eng = GameEngine(content, Random(seed))
            val s = eng.newGame()
            repeat(500) {
                val activeId = s.activeEventId
                if (activeId != null) {
                    val n = eng.snapshot(s).activeEvent!!.choices.size
                    eng.chooseEvent(s, activeId, chooser.nextInt(n))
                } else {
                    val snap = eng.snapshot(s)
                    val acts = snap.objects.flatMap { it.actions }.filter { it.enabled }.map { it.id }
                    val others = snap.locations.map { it.id }.filter { it != s.currentLocation }
                    if (others.isNotEmpty() && chooser.nextDouble() < 0.15) {
                        try { eng.move(s, others[chooser.nextInt(others.size)]) } catch (_: GameError) { }
                    } else if (acts.isNotEmpty()) {
                        eng.executeAction(s, acts[chooser.nextInt(acts.size)])
                    } else if (s.currentLocation != "room") {
                        // 기력 고갈로 할 게 없는 상태: 방으로 돌아가 자야 한다 (게임이 막히면 안 됨)
                        s.status["energy"] = maxOf(s.energy, 5)
                        eng.move(s, "room")
                    } else {
                        fail("막다른 상태: ${s.status}")
                    }
                }
                assertTrue(s.energy in 0..100)
                assertTrue(s.hyperfocus in 0..100)
                assertTrue(s.money >= 0 && s.food >= 0)
                assertTrue(s.period in GameEngine.PERIODS)
                assertTrue(s.day >= 1)
            }
        }
    }
}
