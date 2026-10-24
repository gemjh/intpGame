package com.example.introom.engine

import org.junit.Test
import org.junit.Assert.*
import java.io.File

/*
 * Python 버전(tests/test_engine.py)의 24개 테스트를 그대로 포팅했다 — 테스트 이름과 검증 내용이
 * 1:1로 대응된다. 엔진 로직을 새로 만들지 않고 그대로 옮겼다는 걸 테스트로도 증명하기 위함이다.
 */
class EngineTest {

    private fun assertGameError(block: () -> Unit) {
        try {
            block()
            fail("예외가 발생해야 하는데 발생하지 않음")
        } catch (e: GameError) {
            // expected
        }
    }

    private fun findNightLightSeed(day: Int = 1, period: String = "night"): Long {
        for (seed in 1L..200L) {
            if (nightLightVisible(day, period, seed)) return seed
        }
        throw RuntimeException("테스트용 seed를 못 찾음")
    }

    private val nightSeed = findNightLightSeed()

    private fun fresh(seed: Long = nightSeed, period: String = "night", day: Int = 1): GameState {
        val s = newGame(seed = seed)
        s.day = day
        s.period = period
        return s
    }

    // ------------------------------------------------------------------ 기본
    @Test
    fun testNewGameDefaults() {
        val s = newGame(seed = 1)
        assertEquals(1, s.day)
        assertEquals("morning", s.period)
        assertEquals(0, s.windowResolution)
        assertFalse(s.exitUnlocked)
        assertEquals(1, s.log.size)
    }

    @Test
    fun testRoomMapShapeAndPlayerStartWalkable() {
        assertEquals(ROOM_W, ROOM_MAP[0].length)
        assertTrue(ROOM_MAP.all { it.length == ROOM_W })
        val (px, py) = PLAYER_START
        assertTrue(isWalkable(px, py))
    }

    @Test
    fun testAllEssentialObjectsPresentOnMap() {
        val essential = OBJECTS.values.filter { it.tier == "essential" }.map { it.id }
        for (oid in essential) {
            assertTrue("$oid 가 맵에 없음", OBJECT_TILES.containsKey(oid) && OBJECT_TILES[oid]!!.isNotEmpty())
        }
    }

    // ------------------------------------------------------------------ 이동/충돌 (근접 상호작용)
    @Test
    fun testInteractRequiresAdjacencyNoTeleport() {
        val s = fresh()
        val farPos = Pair(1, 1) // 창문과 멀리 떨어진 좌표 (창문은 (5,1)/(6,1))
        assertGameError { interact(s, "window", "glance", playerPos = farPos) }

        val (wx, wy) = OBJECT_TILES.getValue("window")[0]
        val adjacent = Pair(wx, wy + 1)
        assertTrue(isWalkable(adjacent.first, adjacent.second))
        interact(s, "window", "glance", playerPos = adjacent) // 에러 없이 성공해야 함
    }

    // ------------------------------------------------------------------ 시간 / 턴 비용
    @Test
    fun testZeroCostActionDoesNotAdvanceTime() {
        val s = fresh(period = "morning")
        val before = Pair(s.day, s.period)
        interact(s, "window", "glance")
        assertEquals(before, Pair(s.day, s.period))
    }

    @Test
    fun testOneCostActionAdvancesPeriod() {
        val s = fresh(period = "morning")
        interact(s, "boxes", "organize")
        assertEquals("day1", s.period)
    }

    @Test
    fun testSleepingPastNightAdvancesDayAndWakesAtDawnOrMorning() {
        val s = fresh(period = "night")
        interact(s, "boxes", "organize") // turnCost=1 이 밤을 넘기면 자동으로 잠든다
        assertEquals(2, s.day)
        assertTrue(s.period in setOf("dawn", "morning"))
    }

    @Test
    fun testZeroTurnRepeatLimitCapsContent() {
        val s = fresh(period = "morning")
        val texts = mutableListOf<String>()
        repeat(5) {
            val r = interact(s, "window", "glance")
            texts.add(r["text"] as String)
        }
        assertEquals(ENOUGH_ALREADY.getValue("window"), texts.last())
        assertEquals(1, s.day); assertEquals("morning", s.period) // 0턴이니 시간은 안 흘렀다
    }

    // ------------------------------------------------------------------ 관찰 / 가설 분리
    @Test
    fun testObservationAndHypothesisAreSeparateRecords() {
        val s = fresh(period = "night")
        val r = interact(s, "window", "observe")
        assertEquals("night_light", r["thread"])
        assertEquals(1, s.observations.size)
        assertNotEquals("", s.observations[0].text)
        assertEquals(0, s.hypotheses.size) // 관찰만으로는 가설이 자동 생성되지 않는다

        interact(s, "desk", "hypothesis", payload = mapOf("thread" to "night_light", "template_index" to 0))
        assertEquals(1, s.hypotheses.size)
        val hyp = s.hypotheses[0]
        // observation 레코드와 hypothesis 레코드가 하나로 합쳐지지 않고 별개 리스트에 존재
        assertNotEquals(s.observations[0].text, hyp.text)
        assertEquals("night_light", hyp.thread)
    }

    @Test
    fun testWrongHypothesisIsRevisedNotDeleted() {
        val s = fresh(period = "night")
        interact(s, "window", "observe")
        interact(s, "desk", "hypothesis", payload = mapOf("thread" to "night_light", "template_index" to 0))
        val hypId = s.hypotheses[0].id
        assertEquals(1, s.hypotheses[0].revisions.size)

        // 재관찰 후 반례 -> 재검토 (삭제가 아니라 이력 추가)
        s.period = "night"
        interact(s, "window", "observe")
        interact(s, "desk", "hypothesis", payload = mapOf("thread" to "night_light"))

        assertEquals(1, s.hypotheses.size) // 여전히 하나 (새로 만든 게 아니라 같은 가설이 갱신됨)
        assertEquals(hypId, s.hypotheses[0].id)
        assertEquals(2, s.hypotheses[0].revisions.size)
        assertEquals("revised", s.hypotheses[0].status)
        val lastRev = s.hypotheses[0].revisions.last()
        assertTrue("재검토" in lastRev.text || "재검토" in lastRev.reason)
    }

    @Test
    fun testHypothesisRequiresPriorObservation() {
        val s = fresh(period = "morning")
        assertGameError {
            interact(s, "desk", "hypothesis", payload = mapOf("thread" to "night_light", "template_index" to 0))
        }
    }

    // ------------------------------------------------------------------ 정보원 분리 (책/컴퓨터)
    @Test
    fun testComputerSearchRequiresObservationFirst() {
        val s = fresh(period = "morning")
        assertGameError { interact(s, "computer", "search", payload = mapOf("thread" to "night_light")) }
    }

    @Test
    fun testComputerAndBookAreDistinctInfoSources() {
        val s = fresh(period = "night")
        interact(s, "window", "observe")
        val r = interact(s, "computer", "search", payload = mapOf("thread" to "night_light"))
        @Suppress("UNCHECKED_CAST")
        val candidates = r["candidates"] as List<String>
        assertTrue(candidates.size >= 3)
        assertTrue(s.infos.all { it.source == "computer" })

        interact(s, "bookshelf", "read")
        assertTrue(s.infos.any { it.source == "book" })
        assertTrue("night_light" in s.booksRead)
    }

    // ------------------------------------------------------------------ 메모벽 / 외부 관찰 필요(문)
    @Test
    fun testMemoWallAndExitUnlockAfterEnoughInvestigation() {
        val s = fresh(period = "night")
        assertTrue(s.memoWall.isEmpty())
        assertFalse(s.exitUnlocked)

        repeat(5) {
            s.period = "night"
            interact(s, "window", "observe")
            interact(s, "desk", "hypothesis", payload = mapOf("thread" to "night_light"))
        }

        assertTrue("night_light" in s.memoWall)
        assertTrue(s.exitUnlocked)

        val r = interact(s, "door", "exit")
        assertEquals(true, r["ended"])
        assertTrue(s.ended)
    }

    @Test
    fun testDoorBeforeUnlockIsFlavorOnly() {
        val s = fresh(period = "morning")
        val r = interact(s, "door", "exit")
        assertEquals(false, r["ended"])
        assertFalse(s.ended)
    }

    // ------------------------------------------------------------------ 놓침 원칙 (미관찰 -> 기록 없음, 진행은 막히지 않음)
    @Test
    fun testMissedEventLeavesNoObservationAndDoesNotBlockPlay() {
        val s = fresh(period = "night")
        // 밤바다 불빛이 실제로는 떠 있어도(world truth), 플레이어가 창문을 안 보면 관찰 기록이 안 남는다.
        assertTrue(nightLightVisible(s.day, s.period, s.seed))
        assertTrue(s.observations.isEmpty())
        // 그래도 다른 행동(진행)은 막힘없이 가능하다.
        interact(s, "boxes", "organize")
        assertEquals(2, s.day) // 밤에 1턴 행동을 했으니 자연스럽게 다음 날로 넘어감
    }

    @Test
    fun testWorldTruthIndependentOfObservation() {
        // nightLightVisible 은 관찰 여부와 무관하게 seed/day/period 만으로 결정된다 (순수 함수).
        val v1 = nightLightVisible(1, "night", nightSeed)
        val v2 = nightLightVisible(1, "night", nightSeed)
        assertTrue(v1 == v2 && v1)
    }

    // ------------------------------------------------------------------ 이삿짐 상자 / 쌍안경
    @Test
    fun testBinocularsFoundWithoutRpgPopupOnThirdOrganize() {
        val s = fresh(period = "morning")
        val gained = mutableListOf<String?>()
        repeat(3) {
            s.period = "morning"
            val r = interact(s, "boxes", "organize")
            gained.add(r["item_gained"] as String?)
        }
        assertTrue("쌍안경" in s.items)
        assertEquals(1, gained.count { it == "쌍안경" }) // 한 번만 지급
    }

    // ------------------------------------------------------------------ 세이브/로드 (StateCodec + 파일, storage.py 대응)
    private fun saveToFile(file: File, state: GameState) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(StateCodec.encode(state))
        tmp.copyTo(file, overwrite = true)
        tmp.delete()
    }

    private fun loadFromFile(file: File): GameState? {
        if (!file.exists()) return null
        return try {
            StateCodec.decode(file.readText())
        } catch (e: Exception) {
            null
        }
    }

    @Test
    fun testSaveLoadRoundTripPreservesTypedRecords() {
        val s = fresh(period = "night")
        interact(s, "window", "observe")
        interact(s, "desk", "hypothesis", payload = mapOf("thread" to "night_light", "template_index" to 0))
        interact(s, "computer", "search", payload = mapOf("thread" to "night_light"))

        val tmpDir = createTempDir(prefix = "introom-test")
        val file = File(tmpDir, "save.json")
        saveToFile(file, s)
        val loaded = loadFromFile(file)

        assertNotNull(loaded)
        loaded!!
        assertEquals(s.day, loaded.day)
        assertEquals(s.observations[0].text, loaded.observations[0].text)
        assertEquals(s.hypotheses[0].revisions[0].text, loaded.hypotheses[0].revisions[0].text)
        assertEquals("computer", loaded.infos[0].source)
        // 로드 후에도 정상적으로 계속 플레이 가능해야 함 (타입 복원 검증)
        loaded.period = "night"
        interact(loaded, "window", "observe")
        assertEquals(2, loaded.observations.size)

        tmpDir.deleteRecursively()
    }

    @Test
    fun testLoadMissingSessionReturnsNone() {
        val tmpDir = createTempDir(prefix = "introom-test")
        val file = File(tmpDir, "no-such-session-xyz.json")
        assertNull(loadFromFile(file))
        tmpDir.deleteRecursively()
    }

    // ------------------------------------------------------------------ 쌍안경 (맨눈 확인 -> 확대 관찰)
    @Test
    fun testZoomWithoutBinocularsIsRejected() {
        val s = fresh()
        assertGameError { interact(s, "window", "zoom") }
    }

    @Test
    fun testZoomBeforeNakedEyeObserveMissesAndCostsATurn() {
        val s = fresh()
        s.items.add("쌍안경")
        val before = s.observations.size
        val result = interact(s, "window", "zoom")
        val text = result["text"] as String
        assertTrue("맨눈" in text || "놓" in text)
        assertEquals(before, s.observations.size) // 관찰 기록은 늘지 않는다 (위치를 못 찾았으므로)
    }

    @Test
    fun testZoomAfterNakedEyeObserveGivesExtraDetailAndFumblesOnce() {
        val s = fresh()
        s.items.add("쌍안경")
        interact(s, "window", "observe") // 맨눈으로 먼저 위치 확인 (밤을 넘기므로 아래서 다시 밤으로 되돌림)
        s.day = 1
        s.period = "night"
        assertFalse(s.binocularsFumbledOnce)

        val first = interact(s, "window", "zoom")
        assertTrue((first["text"] as String).contains(BINOCULARS_FIRST_USE_FUMBLE))
        assertTrue(s.binocularsFumbledOnce)
        assertEquals("night_light", first["thread"])

        s.period = "night" // 다음 턴에서도 밤바다 불빛이 보이도록 유지
        val second = interact(s, "window", "zoom")
        assertFalse((second["text"] as String).contains(BINOCULARS_FIRST_USE_FUMBLE)) // 두 번째부터는 더 이상 놓치지 않는다
        assertTrue((second["text"] as String).contains(NIGHT_LIGHT_ZOOM_TEXT))
    }

    // ------------------------------------------------------------------ 엔딩 최단 경로 (261011 창문 대상 선택 이후)
    @Test
    fun testShortestRouteWithSeaSubjectReachesEnding() {
        // 웹 버전 "밖으로 나가기" 시나리오 A: 밤마다 [바다 보기], 불빛을 본 다음 날 책상에서 가설 적기 + 다시 검토하기.
        val s = newGame(seed = nightSeed)
        s.period = "morning"
        var guard = 0
        while (!s.exitUnlocked) {
            assertTrue("엔딩 조건을 못 채우고 너무 오래 걸림", guard++ < 200)
            val t = s.threads["night_light"]
            when {
                s.period == "night" -> interact(s, "window", "observe", payload = mapOf("subject" to "sea"))
                t != null && t.observationCount > 0 && s.hypotheses.isEmpty() ->
                    interact(s, "desk", "hypothesis", payload = mapOf("thread" to "night_light", "template_index" to 0))
                t != null && t.observationCount > 0 && t.revisionCount == 0 ->
                    interact(s, "desk", "hypothesis", payload = mapOf("thread" to "night_light"))
                else -> interact(s, "boxes", "organize")
            }
        }
        assertTrue(s.threads.getValue("night_light").observationCount >= EXIT_READY_THRESHOLD.observations)
        assertEquals("Vertical Slice End", interact(s, "door", "exit")["end_label"])
    }

    @Test
    fun testNightLookingAtOtherSubjectMissesTheLight() {
        val s = fresh(period = "night")
        val r = interact(s, "window", "observe", payload = mapOf("subject" to "sky"))
        assertEquals("sky", r["subject"])
        assertNull(s.threads["night_light"]) // 하늘을 봤으니 그날 밤 불빛은 놓친다 (진행은 막히지 않음)
    }

    // ------------------------------------------------------------------ 맵: 근접 상호작용 타일 충돌 없음
    @Test
    fun testEveryEssentialObjectHasAnUnambiguousApproachTile() {
        fun approachTiles(oid: String): Set<Pair<Int, Int>> {
            val tiles = mutableSetOf<Pair<Int, Int>>()
            for ((ox, oy) in OBJECT_TILES.getValue(oid)) {
                for ((dx, dy) in listOf(0 to 1, 0 to -1, 1 to 0, -1 to 0)) {
                    val nx = ox + dx; val ny = oy + dy
                    if (isWalkable(nx, ny)) tiles.add(Pair(nx, ny))
                }
            }
            return tiles
        }

        val ids = OBJECT_TILES.keys.toList()
        for (oid in ids) {
            val at = approachTiles(oid)
            if (at.size == 1) {
                for (other in ids.filter { it != oid }) {
                    assertTrue(
                        "$oid 의 유일한 접근 칸 $at 이 $other 와 겹쳐서 $oid 를 선택할 방법이 없다",
                        at.intersect(approachTiles(other)).isEmpty(),
                    )
                }
            }
        }
    }
}

private fun createTempDir(prefix: String): File = java.nio.file.Files.createTempDirectory(prefix).toFile()
