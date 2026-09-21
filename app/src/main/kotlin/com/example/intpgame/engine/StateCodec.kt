package com.example.intpgame.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * [GameState] <-> JSON 문자열 (세이브 파일).
 *
 * 필드를 직접 읽고 쓴다. 나중 버전에서 필드가 추가돼도 옛 세이브를 기본값으로 읽을 수 있고,
 * 파일이 깨졌으면 null 을 돌려줘서 호출 측이 새 게임으로 대체할 수 있다.
 */
object StateCodec {
    private const val VERSION = 1
    private val gson: Gson = GsonBuilder().create()

    fun encode(s: GameState): String {
        val o = JsonObject()
        o.addProperty("version", VERSION)
        o.add("stats", intMap(s.stats))
        o.add("status", intMap(s.status))
        o.addProperty("day", s.day)
        o.addProperty("period", s.period)
        o.addProperty("currentLocation", s.currentLocation)
        o.add("unlockedLocations", strList(s.unlockedLocations))
        o.add("actionCounts", intMap(s.actionCounts))
        o.add("interests", intMap(s.interests))
        o.add("discoveredInfo", strList(s.discoveredInfo))
        s.activeEventId?.let { o.addProperty("activeEventId", it) }
        o.add("logs", strList(s.logs))
        s.lastAction?.let { o.addProperty("lastAction", it) }
        o.add("seenEvents", strList(s.seenEvents))
        o.add("eventLastDay", intMap(s.eventLastDay))
        o.addProperty("pendingAdvance", s.pendingAdvance)
        o.addProperty("pendingSlept", s.pendingSlept)
        return gson.toJson(o)
    }

    /** 읽기에 실패하거나 내용이 콘텐츠와 맞지 않으면 null. */
    fun decode(json: String, content: GameContent): GameState? {
        return try {
            val o = JsonParser.parseString(json).asJsonObject
            val s = GameState()
            readInts(o, "stats")?.let { s.stats.putAll(it) }
            readInts(o, "status")?.let { s.status.putAll(it) }
            s.day = o.get("day")?.asInt ?: 1
            s.period = o.get("period")?.asString ?: "morning"
            s.currentLocation = o.get("currentLocation")?.asString ?: "room"
            readStrs(o, "unlockedLocations")?.let { s.unlockedLocations.clear(); s.unlockedLocations.addAll(it) }
            readInts(o, "actionCounts")?.let { s.actionCounts.putAll(it) }
            readInts(o, "interests")?.let { s.interests.putAll(it) }
            readStrs(o, "discoveredInfo")?.let { s.discoveredInfo.addAll(it) }
            s.activeEventId = o.get("activeEventId")?.takeIf { !it.isJsonNull }?.asString
            readStrs(o, "logs")?.let { s.logs.addAll(it) }
            s.lastAction = o.get("lastAction")?.takeIf { !it.isJsonNull }?.asString
            readStrs(o, "seenEvents")?.let { s.seenEvents.addAll(it) }
            readInts(o, "eventLastDay")?.let { s.eventLastDay.putAll(it) }
            s.pendingAdvance = o.get("pendingAdvance")?.asInt ?: 0
            s.pendingSlept = o.get("pendingSlept")?.asBoolean ?: false
            if (isConsistent(s, content)) s else null
        } catch (e: Exception) {
            null
        }
    }

    private fun isConsistent(s: GameState, c: GameContent): Boolean {
        if (s.period !in GameEngine.PERIODS) return false
        if (s.day < 1) return false
        if (s.currentLocation !in c.locations) return false
        if (s.currentLocation !in s.unlockedLocations) return false
        if (s.unlockedLocations.any { it !in c.locations }) return false
        if (s.activeEventId != null && c.events.none { it.id == s.activeEventId }) return false
        if (c.labels.stats.keys.any { it !in s.stats }) return false
        if (c.labels.status.keys.any { it !in s.status }) return false
        return true
    }

    private fun intMap(m: Map<String, Int>): JsonObject {
        val o = JsonObject()
        for ((k, v) in m) o.addProperty(k, v)
        return o
    }

    private fun strList(l: List<String>): JsonArray {
        val a = JsonArray()
        for (s in l) a.add(s)
        return a
    }

    private fun readInts(o: JsonObject, key: String): Map<String, Int>? {
        val el = o.get(key)?.takeIf { it.isJsonObject } ?: return null
        val out = LinkedHashMap<String, Int>()
        for ((k, v) in el.asJsonObject.entrySet()) out[k] = v.asInt
        return out
    }

    private fun readStrs(o: JsonObject, key: String): List<String>? {
        val el = o.get(key)?.takeIf { it.isJsonArray } ?: return null
        return el.asJsonArray.map { it.asString }
    }
}
