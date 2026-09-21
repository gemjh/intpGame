package com.example.intpgame.engine

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * assets 의 objects.json / actions.json / events.json 을 읽어 [GameContent] 로 만든다.
 *
 * Gson 의 리플렉션 매핑 대신 트리(JsonObject)를 직접 읽는다. Kotlin 기본값은 Gson 이 무시하기 때문에
 * (누락된 키가 null 이 되어 NPE 가 난다), 콘텐츠 JSON 에서 선택 항목을 생략해도 안전하도록 하기 위함이다.
 */
object ContentLoader {

    fun load(objectsJson: String, actionsJson: String, eventsJson: String): GameContent {
        val objectsRoot = parse(objectsJson)
        val labelsJson = objectsRoot.obj("labels") ?: throw IllegalArgumentException("objects.json: labels 없음")
        val labels = Labels(
            stats = labelsJson.strMap("stats"),
            status = labelsJson.strMap("status"),
            interests = labelsJson.strMap("interests"),
            periods = labelsJson.strMap("periods"),
        )

        val locations = LinkedHashMap<String, LocationDef>()
        val locationsJson = objectsRoot.obj("locations") ?: throw IllegalArgumentException("objects.json: locations 없음")
        for ((locId, locEl) in locationsJson.entrySet()) {
            val loc = locEl.asJsonObject
            val objects = loc.get("objects").asJsonArray.map { oEl ->
                val o = oEl.asJsonObject
                ObjectDef(
                    id = o.str("id"),
                    name = o.str("name"),
                    icon = o.str("icon"),
                    description = o.str("description"),
                    actions = o.strList("actions"),
                )
            }
            locations[locId] = LocationDef(locId, loc.str("name"), loc.str("description"), objects)
        }

        val actions = LinkedHashMap<String, ActionDef>()
        val actionsRoot = parse(actionsJson).obj("actions") ?: throw IllegalArgumentException("actions.json: actions 없음")
        for ((id, el) in actionsRoot.entrySet()) {
            val a = el.asJsonObject
            actions[id] = ActionDef(
                id = id,
                label = a.str("label"),
                hint = a.str("hint"),
                timeCost = a.int("time_cost", 1),
                skipToNextDay = a.bool("skip_to_next_day", false),
                effects = effects(a.obj("effects")),
                logs = a.strList("logs"),
            )
        }

        val events = parse(eventsJson).get("events").asJsonArray.map { el ->
            val e = el.asJsonObject
            EventDef(
                id = e.str("id"),
                title = e.str("title"),
                description = e.str("description"),
                once = e.bool("once", true),
                cooldownDays = e.int("cooldown_days", 0),
                conditions = conditions(e.obj("trigger_conditions")),
                choices = e.get("choices").asJsonArray.map { cEl ->
                    val c = cEl.asJsonObject
                    ChoiceDef(c.str("text"), c.str("result"), effects(c.obj("effects")))
                },
            )
        }

        val content = GameContent(labels, locations, actions, events)
        validate(content)
        return content
    }

    // ------------------------------------------------------------ 변환
    private fun effects(o: JsonObject?): Effects {
        if (o == null) return Effects()
        return Effects(
            stats = o.intMap("stats"),
            status = o.intMap("status"),
            interests = o.intMap("interests"),
            unlockLocations = o.strList("unlock_locations"),
            discover = o.strList("discover"),
            advanceTime = o.int("advance_time", 0),
        )
    }

    private fun conditions(o: JsonObject?): Conditions {
        if (o == null) return Conditions()
        val last = o.get("last_action")
        val lastActions: List<String>? = when {
            last == null || last.isJsonNull -> null
            last.isJsonArray -> last.asJsonArray.map { it.asString }
            else -> listOf(last.asString)
        }
        return Conditions(
            lastAction = lastActions,
            location = o.strOrNull("location"),
            periodIn = if (o.has("period_in")) o.strList("period_in") else null,
            dayGte = o.int("day_gte", 1),
            actionCountsGte = o.intMap("action_counts_gte"),
            interestsGte = o.intMap("interests_gte"),
            statsGte = o.intMap("stats_gte"),
            statusGte = o.intMap("status_gte"),
            statusLte = o.intMap("status_lte"),
            infoPresent = o.strList("info_present"),
            infoAbsent = o.strList("info_absent"),
            chance = o.get("chance")?.takeIf { it.isJsonPrimitive }?.asDouble,
        )
    }

    /** 콘텐츠 작성 실수를 게임 도중이 아니라 시작할 때 바로 알 수 있게 한다. */
    private fun validate(c: GameContent) {
        val located = HashSet<String>()
        for (loc in c.locations.values) for (obj in loc.objects) for (aid in obj.actions) {
            require(aid in c.actions) { "objects.json 이 정의되지 않은 행동을 참조함: $aid" }
            located += aid
        }
        for (aid in c.actions.keys) require(aid in located) { "어느 오브젝트에도 연결되지 않은 행동: $aid" }

        fun checkEffects(where: String, fx: Effects) {
            for (k in fx.stats.keys) require(k in c.labels.stats) { "$where: 알 수 없는 능력치 $k" }
            for (k in fx.status.keys) require(k in c.labels.status) { "$where: 알 수 없는 상태값 $k" }
            for (l in fx.unlockLocations) require(l in c.locations) { "$where: 알 수 없는 장소 $l" }
        }
        for (a in c.actions.values) {
            checkEffects(a.id, a.effects)
            require(a.logs.isNotEmpty()) { "${a.id}: logs 가 비어 있음" }
        }
        for (e in c.events) {
            require(e.choices.isNotEmpty()) { "${e.id}: 선택지가 없음" }
            for (ch in e.choices) checkEffects(e.id, ch.effects)
        }
    }

    // ------------------------------------------------------------ JSON 헬퍼
    private fun parse(text: String): JsonObject = JsonParser.parseString(text).asJsonObject

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.strOrNull(key: String): String? =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString

    private fun JsonObject.str(key: String): String = strOrNull(key) ?: ""

    private fun JsonObject.int(key: String, default: Int): Int =
        get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: default

    private fun JsonObject.bool(key: String, default: Boolean): Boolean =
        get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: default

    private fun JsonObject.strList(key: String): List<String> =
        get(key)?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString } ?: emptyList()

    private fun JsonObject.intMap(key: String): Map<String, Int> {
        val o = obj(key) ?: return emptyMap()
        val out = LinkedHashMap<String, Int>()
        for ((k, v) in o.entrySet()) out[k] = (v as JsonElement).asInt
        return out
    }

    private fun JsonObject.strMap(key: String): Map<String, String> {
        val o = obj(key) ?: return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((k, v) in o.entrySet()) out[k] = v.asString
        return out
    }
}
