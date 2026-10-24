package com.example.introom.engine

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/*
 * 2차 범위 테스트: 준비된 질문 / 지식 단계 / 파도 주기 탐구 / 기록 화면 (요구사항명세서 261011 4·5·8장).
 * Python 버전(tests/test_inquiry.py)의 포트 — 테스트 이름과 검증 내용이 1:1로 대응된다.
 */
class InquiryTest {

    /** 앞마당 고양이가 끼어들지 않고, 1·2일차 파도 주기가 2초 이상 다른 seed. */
    private val seed: Long = (1L until 5000L).first { sd ->
        listOf("morning", "day1").none { catInYard(1, it, sd) } &&
            Math.abs(wavePeriod(1, sd) - wavePeriod(2, sd)) >= 2.0
    }

    private fun fresh(period: String = "morning", day: Int = 1): GameState {
        val s = newGame(seed = seed)
        s.day = day
        s.period = period
        return s
    }

    private fun obs(id: String) = WINDOW_OBSERVATIONS.getValue(id)

    private fun see(s: GameState, oid: String, period: String = "morning"): Result {
        s.period = period
        val o = obs(oid)
        windowPickOverride = { _, _ -> o.subject to o }
        try {
            return interact(s, "window", "observe", payload = mapOf("subject" to o.subject))
        } finally {
            windowPickOverride = null
        }
    }

    private fun act(s: GameState, obj: String, action: String, period: String? = null, payload: Map<String, Any?> = emptyMap()): Result {
        if (period != null) s.period = period
        return interact(s, obj, action, payload = payload)
    }

    private fun hold(s: GameState, qid: String, st: String = "held") =
        act(s, "records", "question", payload = mapOf("question" to qid, "question_state" to st))

    private fun wavePeriodStatus(s: GameState) = s.knowledge.getValue("wave_period").status

    @After
    fun clearOverride() {
        windowPickOverride = null
    }

    // ------------------------------------------------------------------ 질문 (QST-01, REC-04)
    @Test
    fun testQuestionAriseFromObservationAndCanBeHeldOrSkipped() {
        val s = fresh()
        assertEquals(listOf(mapOf("id" to "wave_interval_constant", "text" to "파도 간격은 정말 일정할까?")), see(s, "SEA-004")["questions"])
        assertEquals("arisen", s.questions.getValue("wave_interval_constant").state)
        assertEquals(emptyList<Any>(), see(s, "SEA-004")["questions"])
        val before = s.day to s.period
        assertEquals(0, hold(s, "wave_interval_constant")["turn_cost"])
        assertEquals(before, s.day to s.period)
        assertEquals("held", s.questions.getValue("wave_interval_constant").state)
    }

    @Test
    fun testQuestionStatesAreNotDoneUndone() {
        val s = fresh()
        see(s, "SEA-018")
        for (st in listOf("held", "revisited", "arisen", "revisited")) {
            hold(s, "tide_vs_wave", st)
            assertEquals(st, s.questions.getValue("tide_vs_wave").state)
        }
        for (bad in listOf("done", "complete", "failed")) {
            assertThrows(GameError::class.java) { hold(s, "tide_vs_wave", bad) }
        }
        assertEquals(listOf("arisen", "held", "revisited", "arisen", "revisited"),
            s.questions.getValue("tide_vs_wave").history.map { it.state })
    }

    @Test
    fun testCannotRegisterQuestionThatNeverArose() {
        val s = fresh()
        assertThrows(GameError::class.java) { hold(s, "tide_vs_wave") }
        assertTrue(s.questions.isEmpty())
    }

    // ------------------------------------------------------------------ 측정
    @Test
    fun testMeasureAtSeaWithoutQuestionThenRegisterLater() {
        val s = fresh()
        see(s, "SEA-001")
        assertTrue(s.questions.isEmpty())
        val r = act(s, "window", "wave_measure", "day1")
        assertEquals(1, r["turn_cost"])
        assertTrue((r["text"] as String).contains("초"))
        assertEquals(1, s.actionsDone.getValue("wave_measure").count)
        @Suppress("UNCHECKED_CAST")
        assertEquals(listOf("wave_interval_constant"), (r["questions"] as List<Map<String, String>>).map { it["id"] })
        hold(s, "wave_interval_constant")
        assertEquals("held", s.questions.getValue("wave_interval_constant").state)
    }

    @Test
    fun testWaveActionsNeedHavingLookedAtWaves() {
        val s = fresh()
        for (a in listOf("wave_count", "wave_measure")) assertThrows(GameError::class.java) { act(s, "window", a) }
        assertTrue(s.actionsDone.isEmpty())
    }

    @Test
    fun testDarkPeriodsCannotMeasureAndCostNothing() {
        val s = fresh()
        see(s, "SEA-001")
        for (p in listOf("evening", "night")) {
            assertEquals(0, act(s, "window", "wave_measure", p)["turn_cost"])
            assertEquals(p, s.period)
        }
        assertFalse("wave_measure" in s.actionsDone)
    }

    @Test
    fun testCountIsNotMeasurementExperience() {
        val s = fresh()
        see(s, "SEA-004")
        act(s, "window", "wave_count", "day1")
        s.knowledge["wave_period"] = KnowledgeEntry()
        assertFalse(requirementsMet(s, obs("SEA-013")))
    }

    @Test
    fun testMeasurementsAreDeterministicWorldValues() {
        val s = fresh()
        see(s, "SEA-001")
        val r = act(s, "window", "wave_measure", "day1")
        assertTrue(Math.abs((r["seconds"] as Double) - wavePeriod(1, seed)) <= 0.5)
    }

    // ------------------------------------------------------------------ 지식 (KNW-01/02)
    @Test
    fun testWaveBookNeedsWavesSeenAndGivesEncounteredOnce() {
        val s = fresh()
        act(s, "bookshelf", "read")
        assertFalse("wave_period" in s.knowledge)
        see(s, "SEA-001")
        assertEquals(listOf("wave_period"), act(s, "bookshelf", "read", "day1")["new_knowledge"])
        assertEquals("encountered", wavePeriodStatus(s))
        repeat(3) { act(s, "bookshelf", "read", "day1") }
        assertEquals(1, s.knowledge.getValue("wave_period").sources.size)
    }

    @Test
    fun testKnowledgeFirstThenMeasureUnlocksSEA013() {
        val s = fresh()
        see(s, "SEA-001")
        act(s, "bookshelf", "read", "day1")
        assertFalse(requirementsMet(s, obs("SEA-013")))
        act(s, "window", "wave_measure", "day1")
        assertTrue(requirementsMet(s, obs("SEA-013")))
    }

    @Test
    fun testMeasureFirstThenKnowledgeUnlocksSEA013() {
        val s = fresh()
        see(s, "SEA-001")
        act(s, "window", "wave_measure", "day1")
        assertFalse(requirementsMet(s, obs("SEA-013")))
        act(s, "bookshelf", "read", "day1")
        assertTrue(requirementsMet(s, obs("SEA-013")))
    }

    @Test
    fun testRepeatReadingOrViewingDoesNotRaiseStage() {
        val s = fresh()
        see(s, "SEA-001")
        act(s, "bookshelf", "read", "day1")
        act(s, "window", "wave_measure", "day1")
        repeat(5) {
            see(s, "SEA-013", "day1")
            act(s, "bookshelf", "read", "day1")
        }
        assertEquals("encountered", wavePeriodStatus(s))
    }

    @Test
    fun testInterpretWithMeasurementMakesUnderstood() {
        val s = fresh()
        see(s, "SEA-001")
        act(s, "bookshelf", "read", "day1")
        act(s, "window", "wave_measure", "day1")
        assertEquals(true, act(s, "desk", "wave_interpret", payload = mapOf("choice" to 2))["knowledge_understood"])
        assertEquals("understood", wavePeriodStatus(s))
    }

    @Test
    fun testInterpretWithOnlyRoughCountsDoesNotUnderstand() {
        val s = fresh()
        see(s, "SEA-001")
        act(s, "bookshelf", "read", "day1")
        act(s, "window", "wave_count", "day1")
        assertEquals("possible", act(s, "desk", "wave_interpret", payload = mapOf("choice" to 2))["status"])
        assertEquals("encountered", wavePeriodStatus(s))
    }

    @Test
    fun testMeasuringOnAnotherDayWithKnowledgeAppliesWithoutForcedOrder() {
        val s = fresh()
        see(s, "SEA-001")
        act(s, "bookshelf", "read", "day1")
        act(s, "window", "wave_measure", "day1")
        s.day = 2
        val r = act(s, "window", "wave_measure", "morning")
        assertTrue((r["text"] as String).contains("지난번(1일차)"))
        assertEquals(true, r["knowledge_applied"])
        assertEquals("applied", wavePeriodStatus(s))
        act(s, "desk", "wave_interpret", payload = mapOf("choice" to 1))
        assertEquals("applied", wavePeriodStatus(s))
    }

    // ------------------------------------------------------------------ 해석/검증
    @Test
    fun testInterpretationFollowsRecordsAndNeverConfirms() {
        val s = fresh()
        see(s, "SEA-001")
        assertThrows(GameError::class.java) { act(s, "desk", "wave_interpret", payload = mapOf("choice" to 0)) }
        act(s, "window", "wave_measure", "day1")
        assertEquals("guess", act(s, "desk", "wave_interpret", payload = mapOf("choice" to 0))["status"])
        s.day = 2
        act(s, "window", "wave_measure", "morning")
        assertEquals("guess", act(s, "desk", "wave_interpret", payload = mapOf("choice" to 0))["status"])
        assertEquals("likely", act(s, "desk", "wave_interpret", payload = mapOf("choice" to 1))["status"])
        val hyp = s.hypotheses.first { it.thread == WAVE_THREAD }
        assertEquals(listOf("guess", "guess", "likely"), hyp.revisions.map { it.status })
        assertTrue(hyp.revisions.none { it.status == "near_certain" })
    }

    @Test
    fun testWaveExplorationDoesNotTouchMemoWallOrExit() {
        val s = fresh()
        see(s, "SEA-001")
        for (d in 1..5) {
            s.day = d
            act(s, "window", "wave_measure", "day1")
            act(s, "desk", "wave_interpret", payload = mapOf("choice" to 1))
        }
        assertTrue(s.memoWall.isEmpty())
        assertFalse(s.exitUnlocked)
        assertFalse(WAVE_THREAD in s.threads)
    }

    // ------------------------------------------------------------------ 중단·재개·저장
    @Test
    fun testInterruptResumeAndSaveKeepState() {
        val s = fresh()
        see(s, "SEA-004")
        hold(s, "wave_interval_constant")
        act(s, "window", "wave_count", "day1")
        val loaded = StateCodec.decode(StateCodec.encode(s))
        assertEquals(s.questions, loaded.questions)
        assertEquals(s.actionsDone, loaded.actionsDone)
        loaded.day = 4
        act(loaded, "bookshelf", "read", "morning")
        act(loaded, "window", "wave_measure", "day1")
        act(loaded, "desk", "wave_interpret", payload = mapOf("choice" to 2))
        val again = StateCodec.decode(StateCodec.encode(loaded))
        assertEquals("understood", wavePeriodStatus(again))
        assertEquals("held", again.questions.getValue("wave_interval_constant").state)
        assertEquals(listOf(WAVE_THREAD), again.hypotheses.map { it.thread })
        assertEquals(1, again.actionsDone.getValue("wave_count").count)
        assertEquals(1, again.actionsDone.getValue("wave_measure").count)
    }

    @Test
    fun testOldSaveWithoutPhase2DataLoads() {
        val s = fresh()
        see(s, "SEA-001")
        val json = com.google.gson.JsonParser.parseString(StateCodec.encode(s)).asJsonObject
        json.remove("questions")
        json.remove("actionsDone")
        val loaded = StateCodec.decode(json.toString())
        assertTrue(loaded.questions.isEmpty() && loaded.actionsDone.isEmpty())
        assertEquals(1, act(loaded, "window", "wave_measure", "day1")["turn_cost"])
    }

    @Test
    fun testQuestionConditionWithoutContentStaysLocked() {
        val s = fresh()
        s.factFlags["wave_direction"] = FactFlag(TimePoint(1, "morning"), mutableListOf(1))
        assertFalse(requirementsMet(s, obs("SEA-035")))
    }

    // ------------------------------------------------------------------ 기록 화면 (REC-03)
    @Test
    fun testRecordsViewHasFourTabsAndIsFree() {
        val s = fresh()
        see(s, "YARD-002")
        see(s, "DOG-004")
        see(s, "SEA-004")
        act(s, "window", "wave_measure", "day1")
        val rec = recordsView(s)
        assertTrue(rec.observations.any { it.text.contains("[파도 측정]") })
        assertTrue(rec.questions.first().text.endsWith("[떠오름]"))
        assertTrue(rec.knowledge.any { it.text.contains("개미 페로몬 의사소통") })
        assertTrue(rec.connections.first().text.contains("개미도 냄새로"))
        assertTrue(waveAvailable(s) && canInterpret(s))
    }
}
