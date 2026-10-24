package com.example.introom.engine

import org.junit.After
import org.junit.Assert.*
import org.junit.Test

/*
 * 자동 연결 시스템 테스트 (자동연결시스템_개발명세서_261012 7장 T01~T10).
 * Python 버전(tests/test_connection.py)의 포트 — 테스트 이름과 검증 내용이 1:1로 대응된다.
 */
class ConnectionTest {

    /** 앞마당 고양이가 끼어들지 않는 seed. C04 용은 1일차 밤에 밤바다 불빛이 보이는 seed. */
    private fun seedFor(nightLightDay1: Boolean): Long {
        for (seed in 1L until 5000L) {
            if (listOf(1, 2).any { d -> listOf("morning", "day1", "day2").any { catInYard(d, it, seed) } }) continue
            if (nightLightDay1 != nightLightVisible(1, "night", seed)) continue
            return seed
        }
        throw RuntimeException("조건에 맞는 seed 없음")
    }

    private val quiet = seedFor(false)
    private val night = seedFor(true)

    private fun fresh(period: String = "day1", day: Int = 1, seed: Long = quiet): GameState {
        val s = newGame(seed = seed)
        s.day = day
        s.period = period
        return s
    }

    private fun obs(id: String) = WINDOW_OBSERVATIONS.getValue(id)

    /** 일반 관찰 선택(가중치 추첨)을 우회해 정해진 문장이 나오게 한다. 나머지 경로는 실제 엔진 그대로. */
    private fun observe(s: GameState, oid: String, period: String = "day1"): Result {
        s.period = period
        val o = obs(oid)
        windowPickOverride = { _, _ -> o.subject to o }
        try {
            return interact(s, "window", "observe", payload = mapOf("subject" to o.subject))
        } finally {
            windowPickOverride = null
        }
    }

    private fun conn(r: Result) = r["connection"]

    @After
    fun clearOverride() {
        windowPickOverride = null
    }

    // ------------------------------------------------------------------ T01 / T02 / T03
    @Test
    fun testT01TriggerWithoutPriorExperienceIsPlainObservation() {
        for (trig in listOf("SEA-007", "SEA-009", "DOG-004", "DOG-023", "SEA-031")) {
            val s = fresh()
            val r = observe(s, trig)
            assertNull(trig, conn(r))
            assertEquals(obs(trig).text, r["text"])
            assertTrue(s.connections.isEmpty())
        }
    }

    @Test
    fun testT02C01WindWaveConnectsOnceAndRecordsEvidence() {
        val s = fresh()
        observe(s, "SEA-009")
        val r = observe(s, "SEA-007")
        assertEquals(mapOf("id" to "sea_wind_wave_contrast_v1", "stage" to 1), conn(r))
        assertTrue((r["text"] as String).startsWith(obs("SEA-007").text + " 전에는 바람이 거의 없는데도"))
        assertFalse((r["text"] as String).contains("너울"))
        val rec = s.connections.getValue("sea_wind_wave_contrast_v1")
        assertEquals(1, rec.stage)
        assertEquals(1, rec.firstDay)
        assertEquals(1, rec.lastDay)
        assertEquals("SEA-007", rec.triggerObservationId)
        assertEquals(listOf("SEA-009", "SEA-007"), rec.evidence)
        assertTrue(s.log.last().contains(r["text"] as String)) // 관찰 + 연결이 하나의 일지 항목
    }

    @Test
    fun testT02C01ReverseOrderUsesSameConnection() {
        val s = fresh()
        observe(s, "SEA-007")
        val r = observe(s, "SEA-009")
        assertEquals(mapOf("id" to "sea_wind_wave_contrast_v1", "stage" to 1), conn(r))
        assertEquals(listOf("SEA-007", "SEA-009"), s.connections.getValue("sea_wind_wave_contrast_v1").evidence)
    }

    @Test
    fun testT02C02AntDogAfterPheromoneKnowledge() {
        for (trig in listOf("DOG-004", "DOG-014", "DOG-018")) {
            val s = fresh()
            observe(s, "YARD-002")
            assertEquals("encountered", s.knowledge.getValue("ant_pheromone").status)
            val r = observe(s, trig)
            assertEquals(trig, mapOf("id" to "ant_dog_scent_v1", "stage" to 1), conn(r))
            assertEquals(listOf("YARD-002", trig), s.connections.getValue("ant_dog_scent_v1").evidence)
        }
    }

    @Test
    fun testT02C03GirlDogAfterSeeingThemTogether() {
        for (prior in listOf("SHOP-012", "SHOP-027", "SHOP-032", "DOG-026")) {
            val s = fresh()
            observe(s, prior)
            val r = observe(s, "DOG-023")
            assertEquals(prior, mapOf("id" to "girl_dog_familiar_v1", "stage" to 1), conn(r))
            assertFalse((r["text"] as String).contains(DOG_NAME)) // 아직 모르는 이름을 단정하지 않는다
        }
    }

    @Test
    fun testT02C04NightLightBuoyInDaytime() {
        val s = fresh(period = "night", seed = night)
        val r = interact(s, "window", "observe", payload = mapOf("subject" to "sea"))
        assertEquals("night_light", r["thread"]) // 실제 기존 세계 이벤트로 밤바다 불빛을 봤다
        val lightId = r["observation_id"] as String
        s.day += 1
        observe(s, "SEA-032", period = "morning") // 항로표지 지식을 접함 (이 관찰도 트리거)
        assertEquals(listOf(lightId, "SEA-032"), s.connections.getValue("night_light_buoy_v1").evidence)
        assertTrue(s.log.last().contains("같은 물체인지는 모르겠지만"))
    }

    @Test
    fun testT03SameTriggerAgainDoesNotRepeat() {
        val s = fresh()
        observe(s, "SEA-009")
        observe(s, "SEA-007")
        for (oid in listOf("SEA-007", "SEA-009", "SEA-007")) {
            val r = observe(s, oid)
            assertNull(conn(r))
            assertEquals(obs(oid).text, r["text"])
        }
        assertEquals(1, s.connections.getValue("sea_wind_wave_contrast_v1").history.size)
    }

    // ------------------------------------------------------------------ T04 / T05
    @Test
    fun testT04SaveReloadKeepsConnectionsWithoutDuplicates() {
        val s = fresh()
        observe(s, "YARD-002")
        observe(s, "DOG-004")
        val loaded = StateCodec.decode(StateCodec.encode(s))
        assertEquals(s.connections, loaded.connections)
        assertNull(conn(observe(loaded, "DOG-018")))
    }

    @Test
    fun testT05ExistingV2Day17SaveLoadsWithoutLoss() {
        val s = fresh()
        repeat(12) { interact(s, "window", "observe", payload = mapOf("subject" to "any")) }
        val json = com.google.gson.JsonParser.parseString(StateCodec.encode(s)).asJsonObject
        assertNotNull(json.remove("connections"))
        json.addProperty("version", 2)
        json.addProperty("day", 17)
        val loaded = StateCodec.decode(json.toString())
        assertTrue(loaded.connections.isEmpty())
        assertEquals(s.observationHistory, loaded.observationHistory)
        assertEquals(s.factFlags, loaded.factFlags)
        assertEquals(s.knowledge, loaded.knowledge)
        assertEquals(s.log, loaded.log)
        assertEquals(s.observations.size, loaded.observations.size)
        val r = interact(loaded, "window", "observe", payload = mapOf("subject" to "sea"))
        assertEquals(1, r["turn_cost"])
        assertTrue(loaded.day >= 17)
    }

    // ------------------------------------------------------------------ T06 ~ T10
    @Test
    fun testT06NoKnowledgeTalkBeforeEncountered() {
        var s = fresh(period = "night", seed = night)
        interact(s, "window", "observe", payload = mapOf("subject" to "sea"))
        s.day += 1
        assertNull(conn(observe(s, "SEA-031", period = "morning"))) // 불빛은 봤지만 항로표지 정보는 아직 못 접함
        s = fresh()
        val r = observe(s, "DOG-018") // 개미 정보 없음
        assertNull(conn(r))
        assertFalse((r["text"] as String).contains("개미"))
    }

    @Test
    fun testT06EncounteredIsNotUnderstood() {
        val s = fresh()
        observe(s, "YARD-002")
        val d = CONNECTIONS.first { it.case == "C02" }.copy(
            id = "fixture_needs_understood",
            prerequisites = listOf(Prerequisite.Knowledge("ant_pheromone", "understood")),
        )
        recordWindowObservation(s, obs("DOG-004"))
        assertNull(checkConnection(s, obs("DOG-004"), listOf(d)))
        assertEquals("encountered", s.knowledge.getValue("ant_pheromone").status)
    }

    @Test
    fun testT06C04NotAtNight() {
        val s = fresh(period = "night", seed = night)
        interact(s, "window", "observe", payload = mapOf("subject" to "sea")) // 관찰 후 시간이 다음 날로 넘어간다
        s.period = "night"
        recordWindowObservation(s, obs("SEA-032"))
        assertNull(checkConnection(s, obs("SEA-032"))) // 낮에 본 부표만 트리거
    }

    @Test
    fun testT07ConnectionCostsNoExtraTurn() {
        val a = fresh()
        val b = fresh()
        observe(b, "SEA-009", period = "morning")
        val ra = observe(a, "SEA-007", period = "morning")
        val rb = observe(b, "SEA-007", period = "morning")
        assertNull(conn(ra))
        assertNotNull(conn(rb))
        assertEquals(1, ra["turn_cost"])
        assertEquals(1, rb["turn_cost"])
        assertEquals(1 to "day1", a.day to a.period)
        assertEquals(1 to "day1", b.day to b.period)
    }

    @Test
    fun testT08ConnectionDoesNotConfirmHypothesesOrThreads() {
        val s = fresh(period = "night", seed = night)
        interact(s, "window", "observe", payload = mapOf("subject" to "sea"))
        s.day += 1
        observe(s, "SEA-032", period = "morning")
        observe(s, "YARD-002")
        fun snap() = StateCodec.encode(
            GameState(hypotheses = s.hypotheses, threads = s.threads, memoWall = s.memoWall,
                exitUnlocked = s.exitUnlocked, knowledge = s.knowledge, questions = s.questions),
        )
        val before = snap()
        assertNull(conn(observe(s, "SEA-031", period = "day1"))) // C04 는 SEA-032 에서 이미 발생 — 반복 안 함
        @Suppress("UNCHECKED_CAST")
        assertEquals("ant_dog_scent_v1", (conn(observe(s, "DOG-004")) as Map<String, Any?>)["id"])
        assertEquals(before, snap())
    }

    @Test
    fun testT09NewEvidenceOutputsNextStageOnly() {
        val s = fresh()
        assertNull(conn(observe(s, "SEA-008"))) // 1단계 전에는 2단계가 나오지 않는다
        observe(s, "SEA-009")
        observe(s, "SEA-007")
        val r = observe(s, "SEA-008")
        assertEquals(mapOf("id" to "sea_wind_wave_contrast_v1", "stage" to 2), conn(r))
        assertTrue((r["text"] as String).contains("짐작"))
        assertFalse((r["text"] as String).contains("전에는 바람이 거의 없는데도"))
        val rec = s.connections.getValue("sea_wind_wave_contrast_v1")
        assertEquals(2, rec.stage)
        assertEquals(listOf(1, 2), rec.history.map { it.stage })
        for (oid in listOf("SEA-008", "SEA-007", "SEA-009")) assertNull(conn(observe(s, oid)))
    }

    @Test
    fun testT10MissingContentIdsFailValidation() {
        val fake = ConnectionDef("fake", "X", 1, listOf("SEA-999"), emptyList(), "x")
        assertThrows(IllegalArgumentException::class.java) { validateConnections(listOf(fake)) }
        // 적용형 지식 ID 는 존재하지만 공급원이 없다 -> 런타임에 조용히 건너뜀
        val noSource = ConnectionDef("fake", "X", 1, listOf("SEA-001"),
            listOf(Prerequisite.Knowledge("swell", "encountered")), "x")
        validateConnections(listOf(noSource))
        val s = fresh()
        recordWindowObservation(s, obs("SEA-001"))
        assertNull(checkConnection(s, obs("SEA-001"), listOf(noSource)))
        assertTrue(s.connections.isEmpty())
        assertFalse("swell" in s.knowledge)
    }

    @Test
    fun testDefinitionsReferenceRealContent() {
        validateConnections(CONNECTIONS)
        assertEquals(setOf("C01", "C02", "C03", "C04"), CONNECTIONS.map { it.case }.toSet())
    }
}
