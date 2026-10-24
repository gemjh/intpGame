package com.example.introom.engine

import kotlin.random.Random

/*
 * 플레이어와 무관하게 돌아가는 세계. (명세서 3장 "세계 상태와 목격")
 *
 * 여기 함수들은 전부 (day, period, seed) 의 순수 함수다 - 플레이어가 봤는지 여부가
 * 세계에서 실제로 일어나는 일에 영향을 주면 안 된다 ("세계는 플레이어를 중심으로 움직이지 않는다").
 * 엔진은 이 '세계의 사실'과 플레이어가 실제로 관찰해서 생긴 Observation 레코드를 항상 분리해서 다룬다.
 *
 * Python 버전(app/engine/world.py)의 포트. 파이썬은 random.Random(문자열)로 시드를 걸지만
 * 코틀린엔 같은 API가 없으므로, 문자열의 안정적인 해시값(String.hashCode()는 JLS에 고정된 공식이라
 * 영구적으로 재현 가능하다)을 시드로 써서 "같은 입력 -> 같은 결과"라는 핵심 성질만 그대로 유지한다
 * (파이썬판과 똑같은 난수 바이트를 낼 필요는 없다 — 완전히 별개 구현이다).
 */

private fun rngFor(day: Int, seed: Long, salt: String): Random {
    val key = "$seed:$day:$salt"
    return Random(key.hashCode().toLong())
}

val WEATHERS = listOf("맑음", "흐림", "비")
private val WEATHER_WEIGHTS = listOf(0.55, 0.3, 0.15)

private fun weightedChoice(r: Random, items: List<String>, weights: List<Double>): String {
    val total = weights.sum()
    var x = r.nextDouble() * total
    for (i in items.indices) {
        x -= weights[i]
        if (x <= 0.0) return items[i]
    }
    return items.last()
}

fun weatherOf(day: Int, seed: Long): String {
    val r = rngFor(day, seed, "weather")
    return weightedChoice(r, WEATHERS, WEATHER_WEIGHTS)
}

/** 주인이 이 날 새벽에 출항하는지. 날씨가 나쁘면 안 나간다. */
fun ownerDepartsAtDawn(day: Int, seed: Long): Boolean {
    if (weatherOf(day, seed) == "비") return false
    val r = rngFor(day, seed, "depart")
    return r.nextDouble() < 0.6
}

/** 출항했다면 그날 낮(day1~day2) 중 귀환한다. */
fun ownerReturnsAtDay(day: Int, seed: Long): Boolean = ownerDepartsAtDawn(day, seed)

fun tankChangedAfterReturn(day: Int, seed: Long): Boolean {
    if (!ownerReturnsAtDay(day, seed)) return false
    val r = rngFor(day, seed, "tank")
    return r.nextDouble() < 0.7
}

/** 횟집 상태: closed / prep / open. */
fun restaurantStatus(day: Int, period: String, seed: Long): String {
    if (period in setOf("dawn", "morning", "day1", "day2")) return "closed"
    if (period == "sunset") return "prep"
    return "open" // evening, night
}

fun catInYard(day: Int, period: String, seed: Long): Boolean {
    val r = rngFor(day, seed, "cat:$period")
    return r.nextDouble() < 0.35
}

/** 밤바다 흰 불빛. 매일 조금씩 다르게 보이되, 이 빌드에서 '정체'는 끝까지 확정하지 않는다. */
fun nightLightVisible(day: Int, period: String, seed: Long): Boolean {
    if (period != "night") return false
    if (weatherOf(day, seed) == "비") return false
    return true
}

data class NightLightVariant(val side: String, val blinking: Boolean, val angleShift: Boolean)

/** 그날그날 불빛이 보이는 모양(위치/깜빡임)을 살짝 바꿔서, 재관찰할 때마다 다른 묘사가 나오게 한다. */
fun nightLightVariant(day: Int, seed: Long): NightLightVariant {
    val r = rngFor(day, seed, "light_variant")
    return NightLightVariant(
        side = if (r.nextDouble() < 0.8) "오른쪽" else "왼쪽으로 살짝 더",
        blinking = r.nextDouble() < 0.7,
        angleShift = r.nextDouble() < 0.25, // 각도가 크게 달라 보이는 드문 날 (반례 재료)
    )
}

fun daytimeMoonVisible(day: Int, period: String, seed: Long): Boolean {
    if (period !in setOf("day1", "day2")) return false
    val r = rngFor(day, seed, "moon")
    return r.nextDouble() < 0.3
}

/**
 * 그날 바다에 들어오는 파도의 실제 주기(초, 0.5 단위). 측정 행동만 이 값을 근사해서 읽을 수 있다.
 * 날마다 달라지는 폭이 있어서, 여러 날 재 보면 차이가 보일 수 있다 (원인은 이 빌드에서 확정하지 않는다).
 */
fun wavePeriod(day: Int, seed: Long): Double {
    val base = 6.5 + Random("$seed:wave_base".hashCode().toLong()).nextDouble() * 2.5
    val r = rngFor(day, seed, "wave_period")
    val v = base + (r.nextDouble() * 4.0 - 2.0)
    return maxOf(4.0, Math.round(v * 2) / 2.0)
}

/** 같은 날이라도 잴 때마다 조금씩 다르게 나오는 측정값 (실제 주기 ±0.5초). */
fun waveReading(day: Int, period: String, seed: Long, salt: String): Double {
    val r = rngFor(day, seed, "$salt:$period")
    return maxOf(4.0, wavePeriod(day, seed) + listOf(-0.5, 0.0, 0.0, 0.5)[r.nextInt(4)])
}
