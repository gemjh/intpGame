package com.example.introom.engine

import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

/*
 * 창문 관찰 시스템 테스트 (명세서 261011 8장 인수 기준).
 * Python 버전(tests/test_window.py)의 27개 테스트를 그대로 포팅했다 — 테스트 이름과 검증 내용이 1:1로 대응된다.
 */
class WindowTest {

    /** 그날 밤바다 불빛/낮달/귀환/고양이 같은 기존 세계 이벤트가 끼어들지 않는 seed (문장 풀만 검증하려고). */
    private fun quietSeed(day: Int = 1): Long {
        for (seed in 1L until 5000L) {
            if (weatherOf(day, seed) != "비") continue // 비 오는 날은 밤바다 불빛이 안 보인다
            if (ownerReturnsAtDay(day, seed) || daytimeMoonVisible(day, "day1", seed) ||
                daytimeMoonVisible(day, "day2", seed)
            ) continue
            if (PERIODS.any { catInYard(day, it, seed) }) continue
            return seed
        }
        throw RuntimeException("조용한 seed를 못 찾음")
    }

    private val quiet = quietSeed()

    private fun fresh(period: String = "day1", day: Int = 1, seed: Long = quiet): GameState {
        val s = newGame(seed = seed)
        s.day = day
        s.period = period
        return s
    }

    private fun obs(id: String) = WINDOW_OBSERVATIONS.getValue(id)

    private fun observe(s: GameState, subject: String?, key: String = "subject"): Result =
        interact(s, "window", "observe", payload = mapOf(key to subject))

    private fun copyOf(s: GameState): GameState = StateCodec.decode(StateCodec.encode(s))

    // ------------------------------------------------------------------ 데이터
    @Test
    fun test148SentencesWithSpecCounts() {
        assertEquals(148, WINDOW_OBSERVATIONS.size)
        val counts = WINDOW_OBSERVATIONS.values.groupingBy { it.subject }.eachCount()
        assertEquals(mapOf("sea" to 38, "sky" to 34, "shop" to 35, "dog" to 31, "yard" to 10), counts)
    }

    @Test
    fun testDataContractFieldsPresent() {
        for (o in WINDOW_OBSERVATIONS.values) {
            assertTrue(o.id, o.subject in WINDOW_SUBJECTS && o.text.isNotEmpty())
            for (f in o.followUpIds) assertTrue("${o.id} -> $f", f in WINDOW_OBSERVATIONS)
            for (r in o.requirements) {
                when (r.type) {
                    "기록" -> assertTrue(r.key, r.key in RECORD_RULES)
                    "지식" -> assertTrue(r.key, r.key in KNOWLEDGE_RULES)
                    "시간" -> assertTrue(r.key, r.key in TIME_RULES)
                    "경험" -> assertTrue(r.key, r.key in EXPERIENCE_RULES)
                    "질문" -> assertTrue(r.key, r.key in QUESTION_RULES)
                    else -> fail("알 수 없는 조건 ${r.type}")
                }
            }
        }
    }

    @Test
    fun testConfirmedTextRevisionsApplied() {
        assertTrue(obs("SEA-038").text.startsWith("바다를 한참 바라봤다. 그러다 배가 고파졌다."))
        assertFalse("물 떨어" in obs("SHOP-018").text)
        assertTrue("체취" in obs("DOG-023").text)
    }

    @Test
    fun testWeatherScenesHaveNoConditions() {
        for (id in listOf("DOG-009", "DOG-010", "DOG-014", "YARD-007", "SHOP-027", "SHOP-030")) {
            assertTrue(id, obs(id).requirements.isEmpty())
        }
    }

    // ------------------------------------------------------------------ 턴 / UX
    @Test
    fun testEachSubjectAndFreeLookCostsExactlyOneTurn() {
        for (subject in WINDOW_SUBJECTS + WINDOW_ANY) {
            val s = fresh(period = "morning")
            val r = observe(s, subject)
            assertEquals(1, r["turn_cost"])
            assertEquals(subject, 1 to "day1", s.day to s.period)
            if (subject != WINDOW_ANY) assertEquals(subject, r["subject"])
            assertTrue((r["text"] as String).isNotEmpty())
        }
    }

    @Test
    fun testObserveSubjectReturnsSentenceOfThatSubject() {
        val s = fresh()
        repeat(20) {
            s.period = "day1"
            val r = observe(s, "dog")
            assertTrue((r["observation_key"] as String).startsWith("DOG-"))
        }
    }

    @Test
    fun testFreeLookPicksAnAvailableSubjectAndEligibleSentence() {
        val s = fresh()
        repeat(40) {
            s.period = "day1"
            val r = observe(s, WINDOW_ANY)
            assertEquals(obs(r["observation_key"] as String).subject, r["subject"])
        }
    }

    @Test
    fun testNoCapOnObservationsPerPeriod() {
        val s = fresh()
        repeat(10) {
            s.period = "day1"
            assertNotNull(observe(s, "sea")["observation_key"]) // 같은 시간대에 반복해도 막히지 않는다
        }
    }

    @Test
    fun testUnknownSubjectRejected() {
        val s = fresh()
        try {
            observe(s, "moon")
            fail("알 수 없는 대상인데 에러가 안 남")
        } catch (e: GameError) {
            // expected
        }
    }

    @Test
    fun testLegacyRegionRestaurantMapsToShop() {
        val s = fresh()
        assertEquals("shop", observe(s, "restaurant", key = "region")["subject"])
    }

    // ------------------------------------------------------------------ 조건 필터
    @Test
    fun testTimeConditionsRespected() {
        val s = fresh(period = "night")
        assertFalse(requirementsMet(s, obs("SEA-002"))) // 시간:낮
        assertTrue(requirementsMet(s, obs("SKY-025")))  // 시간:밤
        s.period = "sunset"
        assertTrue(requirementsMet(s, obs("SKY-015")))
        s.period = "evening"
        assertTrue(requirementsMet(s, obs("DOG-012")))  // 시간:저녁/밤
    }

    @Test
    fun testKnowledgeApplySentenceNotShownBeforeKnowledge() {
        val s = fresh()
        assertFalse(requirementsMet(s, obs("SEA-033")))
        recordWindowObservation(s, obs("SEA-032")) // 발견형: 항로표지 접함
        assertTrue("navigation_marks" in s.knowledge)
        assertTrue(requirementsMet(s, obs("SEA-033")))
    }

    @Test
    fun testMeasurementAndQuestionConditionsStayLocked() {
        val s = fresh()
        s.knowledge["wave_period"] = KnowledgeEntry()
        assertFalse(requirementsMet(s, obs("SEA-013"))) // 경험:측정 없음
        assertFalse(requirementsMet(s, obs("SEA-035"))) // 질문 없음
        assertFalse(requirementsMet(s, obs("DOG-029"))) // 선행 근거 없음(보류)
    }

    @Test
    fun testRecordConditionsTodayVsPreviousDay() {
        val s = fresh(day = 3, period = "morning")
        recordWindowObservation(s, obs("SEA-015"))         // 바위 드러남 (3일차 아침)
        assertTrue(requirementsMet(s, obs("SEA-016")))     // "아까 보이던 바위"
        assertFalse(requirementsMet(s, obs("SEA-017")))    // 같은 날이라 비교 불가
        s.day = 4; s.period = "day1"
        assertFalse(requirementsMet(s, obs("SEA-016")))    // 다음 날엔 '아까'가 아님
        assertFalse(requirementsMet(s, obs("SEA-017")))    // 다른 날이지만 다른 시간대
        s.period = "morning"
        assertTrue(requirementsMet(s, obs("SEA-017")))
    }

    @Test
    fun testLongTermAndMultiRecordConditions() {
        val s = fresh()
        for (id in listOf("YARD-002", "YARD-006")) recordWindowObservation(s, obs(id))
        assertFalse(requirementsMet(s, obs("YARD-010")))
        recordWindowObservation(s, obs("YARD-008"))
        assertTrue(requirementsMet(s, obs("YARD-010")))

        for (d in listOf(1, 2)) {
            s.day = d
            recordWindowObservation(s, obs("DOG-001"))
        }
        assertFalse(requirementsMet(s, obs("DOG-031")))
        s.day = 3
        recordWindowObservation(s, obs("DOG-002"))
        assertTrue(requirementsMet(s, obs("DOG-031")))
    }

    /** 긴 무작위 플레이 동안 출력된 모든 문장은 출력 시점에 조건을 만족했어야 한다. */
    @Test
    fun testConditionedSentencesNeverLeakInLongRandomPlay() {
        val rng = Random(7)
        val s = newGame(seed = 12345)
        repeat(400) {
            val subject = (WINDOW_SUBJECTS + WINDOW_ANY).random(rng)
            val before = copyOf(s)
            val key = observe(s, subject)["observation_key"] as String?
            if (key != null) {
                assertTrue(key, requirementsMet(before, obs(key)))
                assertTrue(key, repeatAllowed(before, obs(key)))
            }
        }
        for (locked in listOf("SEA-013", "SEA-035", "DOG-029", "SEA-012", "SKY-031")) {
            assertFalse(locked, locked in s.observationHistory)
        }
    }

    // ------------------------------------------------------------------ 반복 / 폴백
    @Test
    fun testIntervalRepeatNotTwiceSameDay() {
        val s = fresh()
        recordWindowObservation(s, obs("YARD-004"))
        assertFalse(repeatAllowed(s, obs("YARD-004")))
        s.day += 1
        assertTrue(repeatAllowed(s, obs("YARD-004")))
    }

    @Test
    fun testPreviousSentenceExcludedWhenEnoughAlternatives() {
        val s = fresh()
        recordWindowObservation(s, obs("SEA-001"))
        assertTrue(windowCandidates(s, "sea").none { it.id == "SEA-001" })
    }

    @Test
    fun testRecentSentencesAreDownWeighted() {
        val s = fresh()
        val o = obs("SEA-003")
        val base = windowWeight(s, o)
        recordWindowObservation(s, o)
        assertEquals(base * SEEN_MULT * RECENT_MULT, windowWeight(s, o), 1e-9)
    }

    @Test
    fun testExhaustedSubjectFallsBackToNeutralWithoutDiscovery() {
        val s = fresh()
        for (o in WINDOW_OBSERVATIONS.values) {
            if (o.subject == "yard" && o.repeatPolicy == "interval") recordWindowObservation(s, o) // 앞마당 간격 반복 문장을 오늘 다 봤다
        }
        val knowledgeBefore = s.knowledge.toMap()
        val histBefore = s.observationHistory.keys.toSet()
        val r = observe(s, "yard")
        assertEquals(1, r["turn_cost"])
        assertNull(r["observation_key"])
        assertEquals(NEUTRAL_LINES.getValue("yard"), r["text"])
        assertEquals(knowledgeBefore, s.knowledge)
        assertEquals(histBefore, s.observationHistory.keys)
    }

    // ------------------------------------------------------------------ 기록 / 지식
    @Test
    fun testHistoryFirstLastCountAndNoDuplicateFirstDiscovery() {
        val s = fresh()
        val o = obs("SKY-024")
        val r1 = recordWindowObservation(s, o)
        s.day = 2
        val r2 = recordWindowObservation(s, o)
        val h = s.observationHistory.getValue("SKY-024")
        assertTrue(r1.firstSeen); assertFalse(r2.firstSeen)
        assertEquals(2, h.count); assertEquals(1, h.firstSeen.day); assertEquals(2, h.lastSeen.day)
        assertEquals(listOf("moon_phase"), r1.newKnowledge); assertEquals(emptyList<String>(), r2.newKnowledge)
    }

    @Test
    fun testKnowledgeNotDuplicatedAndStatusNotAutoRaised() {
        val s = fresh()
        for (id in listOf("DOG-018", "DOG-018", "DOG-027")) recordWindowObservation(s, obs(id))
        assertEquals("encountered", s.knowledge.getValue("dog_scent_marking").status)
        assertEquals(1, s.knowledge.getValue("dog_scent_marking").sources.size)
        assertTrue(requirementsMet(s, obs("DOG-015"))) // 개의_후각 (any)
    }

    @Test
    fun testOutputCountAloneDoesNotCreateMeasurementOrNpcHistory() {
        val s = fresh()
        repeat(30) { recordWindowObservation(s, obs("SEA-004")) }
        assertTrue(s.actionsDone.isEmpty() && s.npcRelations.isEmpty() && s.questions.isEmpty())
    }

    @Test
    fun testDogNameSubstitutionAfterNameKnown() {
        val s = fresh()
        assertEquals("강아지", windowSubjectLabel(s, "dog"))
        s.npcRelations["dog"] = mutableMapOf("name_known" to true)
        assertEquals("두식이", windowSubjectLabel(s, "dog"))
        assertTrue(renderWindowText(s, obs("SHOP-012").text).startsWith("소녀가 두식이 집 앞에"))
    }

    // ------------------------------------------------------------------ 저장 호환
    @Test
    fun testSaveLoadKeepsWindowRecords() {
        val s = fresh()
        repeat(5) {
            s.period = "day1"
            observe(s, WINDOW_ANY)
        }
        val loaded = copyOf(s)
        assertEquals(s.observationHistory, loaded.observationHistory)
        assertEquals(s.factFlags, loaded.factFlags)
        assertEquals(s.knowledge, loaded.knowledge)
        observe(loaded, "sea")
    }

    @Test
    fun testV1SaveWithoutNewFieldsLoadsAndPlays() {
        val s = fresh()
        val json = com.google.gson.JsonParser.parseString(StateCodec.encode(s)).asJsonObject
        for (k in listOf("observationHistory", "factFlags", "knowledge", "windowRecent", "questions",
            "actionsDone", "npcRelations", "freeNotes")) {
            assertNotNull(k, json.remove(k))
        }
        json.addProperty("version", 1)
        val loaded = StateCodec.decode(json.toString())
        assertEquals(STATE_VERSION, loaded.version)
        assertTrue(loaded.observationHistory.isEmpty())
        assertEquals(1, observe(loaded, "sky")["turn_cost"])
    }

    @Test
    fun testSnapshotExposesWindowSubjects() {
        @Suppress("UNCHECKED_CAST")
        val window = snapshot(fresh())["window"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val subjects = window["subjects"] as List<Map<String, Any?>>
        assertEquals(WINDOW_SUBJECTS, subjects.map { it["id"] })
        assertEquals(148, window["total"])
    }
}
