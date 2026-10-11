package com.example.introom.engine

/*
 * 창문 관찰 콘텐츠 148문장 + 출현 조건 해석 규칙.
 *
 * 자동 생성 파일 — 직접 고치지 말 것. 원본은 웹 버전 app/engine/window_content.py 이고,
 * intp_room_android/tools/gen_window_content.py 로 다시 만든다 (문장/조건이 웹 버전과 한 글자도 다르지 않게).
 */

data class Requirement(val type: String, val key: String)

data class WindowObservation(
    val id: String,
    val subject: String,
    val text: String,
    val requirements: List<Requirement>,
    val knowledgeMode: String?, // null | "discover" | "apply"
    val knowledgeIds: List<String>,
    val knowledgeLabel: String?,
    val repeatPolicy: String, // "normal" | "interval"
    val effects: List<String>,
    val followUpIds: List<String>,
    val weightClass: String,
)

/** 기록 조건 -> 사실 플래그 판정 규칙 (Window.kt 의 recordOk 참고). */
sealed class RecordRule {
    /** kind: any | today | prev_day | yesterday | same_period_other_day */
    data class Flag(val kind: String, val flag: String) : RecordRule()
    /** 서로 다른 날 n일 이상 */
    data class Days(val flag: String, val n: Int) : RecordRule()
    /** 플래그 n종 이상 */
    data class FlagsMin(val flags: List<String>, val n: Int) : RecordRule()
}

/** 지식 조건: all(전부 필요, 빈 목록이면 충족 불가) 또는 any(하나 이상). */
data class KnowledgeRule(val all: List<String>? = null, val any: List<String>? = null, val blocked: String? = null)

val WINDOW_SUBJECTS = listOf("sea", "sky", "shop", "dog", "yard")
val WINDOW_SUBJECT_LABELS = mapOf("sea" to "바다", "sky" to "하늘", "shop" to "횟집", "dog" to "강아지", "yard" to "앞마당")
const val DOG_NAME = "두식이"

val TIME_RULES: Map<String, Set<String>> = mapOf(
    "낮" to setOf("day1", "day2", "morning"),
    "해질녘" to setOf("sunset"),
    "밤" to setOf("night"),
    "저녁/밤" to setOf("evening", "night"),
)

val KNOWLEDGE_RULES: Map<String, KnowledgeRule> = mapOf(
    "너울" to KnowledgeRule(all = listOf(), blocked = "공급원 없음(2차: 도서관/탐구)"),
    "파도_주기" to KnowledgeRule(all = listOf(), blocked = "공급원 없음(2차: 파도 주기 탐구)"),
    "조석_원리" to KnowledgeRule(all = listOf(), blocked = "공급원 없음(2차)"),
    "지역_조석" to KnowledgeRule(all = listOf(), blocked = "SEA-021 자신이 공급원인 순환 조건 — 콘텐츠 확인 필요"),
    "항로표지" to KnowledgeRule(all = listOf("navigation_marks")),
    "파도_종류" to KnowledgeRule(all = listOf(), blocked = "공급원 없음(2차)"),
    "선박_항적" to KnowledgeRule(all = listOf(), blocked = "공급원 없음(2차)"),
    "고도별_바람" to KnowledgeRule(all = listOf(), blocked = "공급원 없음(2차)"),
    "계절별_일몰" to KnowledgeRule(all = listOf(), blocked = "공급원 없음(3차: 태양 위치 탐구)"),
    "구름_분류" to KnowledgeRule(all = listOf("cumulus_convection", "cirrus_ice")),
    "개의_후각" to KnowledgeRule(any = listOf("dog_scent_marking", "dog_scent_identification", "dog_scent_memory")),
    "사회적_참조" to KnowledgeRule(all = listOf(), blocked = "공급원 없음(3차: 두식이 감각 탐구)"),
)

val BLOCKED_PREFIXES = setOf("경험", "질문")

val RECORD_RULES: Map<String, RecordRule> = mapOf(
    "파도_간격" to RecordRule.Flag("any", "wave_interval"),
    "파도_방향" to RecordRule.Flag("any", "wave_direction"),
    "바위_노출" to RecordRule.Flag("today", "rock_exposed"),
    "다른날_동시간_조위" to RecordRule.Flag("same_period_other_day", "tide"),
    "조위_관찰" to RecordRule.Flag("any", "tide"),
    "서로다른_해양관찰_3종" to RecordRule.FlagsMin(listOf("sea_wave", "sea_tide", "sea_color", "sea_ship", "sea_horizon"), 3),
    "전날_달_관찰" to RecordRule.Flag("yesterday", "moon"),
    "이전날_달_관찰" to RecordRule.Flag("prev_day", "moon"),
    "이전_구름_관찰" to RecordRule.Flag("any", "cloud"),
    "서로다른날_같은시각_달_관찰" to RecordRule.Flag("same_period_other_day", "moon"),
    "이전_일몰위치" to RecordRule.Flag("prev_day", "sunset_view"),
    "이전_별_관측" to RecordRule.Flag("prev_day", "stars"),
    "이전날_횟집청소" to RecordRule.Flag("prev_day", "shop_cleaning"),
    "이전_상자_관찰" to RecordRule.Flag("today", "shop_boxes"),
    "이전_입고_작업" to RecordRule.Flag("today", "fish_delivery"),
    "이전_생선상자_입고" to RecordRule.Flag("today", "fish_box_in"),
    "횟집청소_반복" to RecordRule.Days("shop_cleaning", 2),
    "상자_입고_반복" to RecordRule.Days("fish_delivery", 2),
    "소녀_관찰" to RecordRule.Flag("any", "girl"),
    "강아지_관찰" to RecordRule.Flag("any", "dog_seen"),
    "아저씨_관찰" to RecordRule.Flag("any", "owner"),
    "횟집_장기관찰" to RecordRule.Days("subject:shop", 3),
    "강아지_장기관찰" to RecordRule.Days("subject:dog", 3),
    "이전_햇볕자리" to RecordRule.Flag("any", "sunny_spot"),
    "강아지_행인반응" to RecordRule.Flag("any", "dog_passerby_reaction"),
    "강아지_아저씨반응" to RecordRule.Flag("any", "dog_owner_reaction"),
    "이전_짖음_관찰" to RecordRule.Flag("any", "dog_barking"),
    "이전_고양이_관찰" to RecordRule.Flag("any", "cat"),
    "이전_그림자_관찰" to RecordRule.Flag("today", "shadow"),
    "개미_관찰" to RecordRule.Flag("any", "ants"),
    "식물_관찰" to RecordRule.Flag("any", "plants"),
    "거미줄_관찰" to RecordRule.Flag("any", "spiderweb"),
)

val WEIGHTS: Map<String, Double> = mapOf("daily" to 10.0, "discover" to 8.0, "apply" to 8.0, "past" to 7.0, "memory" to 5.0)
const val SEEN_MULT = 0.7
const val RECENT_MULT = 0.2
const val RECENT_WINDOW = 3

/** 후보가 하나도 없을 때의 중립 문장 (WIN-05). 대상을 정하지 못했을 때는 NEUTRAL_LINE_ANY. */
val NEUTRAL_LINES: Map<String, String> = mapOf(
    "sea" to "바다를 한동안 바라봤다. 지금은 특별히 눈에 들어오는 게 없다.",
    "sky" to "하늘을 한동안 올려다봤다. 지금은 특별히 눈에 들어오는 게 없다.",
    "shop" to "횟집 쪽을 한동안 바라봤다. 지금은 특별히 눈에 들어오는 게 없다.",
    "dog" to "강아지 쪽을 한동안 바라봤다. 지금은 특별히 눈에 들어오는 게 없다.",
    "yard" to "앞마당을 한동안 바라봤다. 지금은 특별히 눈에 들어오는 게 없다.",
)
const val NEUTRAL_LINE_ANY = "창밖을 한동안 바라봤다. 지금은 특별히 눈에 들어오는 게 없다."

/** 기존 세계 이벤트(밤바다 불빛/낮달/아저씨 귀환/고양이) 관찰이 남기는 사실 플래그. */
val LEGACY_EFFECTS: Map<String, List<String>> = mapOf(
    "night_light" to listOf(),
    "moon" to listOf("moon"),
    "restaurant" to listOf("shop_boxes", "fish_delivery", "owner"),
    "cat" to listOf("cat"),
)

/** 검수 시트 148문장 (SEA/SKY/SHOP/DOG/YARD 순서 그대로). 총 148개. */
val WINDOW_OBSERVATIONS: Map<String, WindowObservation> = listOf(
    WindowObservation(
        "SEA-001", "sea",
        "바다가 조용하다. 파도가 없는 건 아니고, 여기서 보면 움직임이 작다. 조용하다는 표현이 좀 부정확한가.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_wave", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-002", "sea",
        "물 위에 햇빛이 잘게 부서진다. 반짝이는 부분을 따라가다 눈이 아파서 그만뒀다.",
        listOf(Requirement("시간", "낮")), null, listOf(), null,
        "normal", listOf("subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-003", "sea",
        "멀리 배 한 척이 지나간다. 한참 봤는데 생각보다 조금 움직였다. 멀어서 느려 보이는 건지, 실제로 느린 건지는 모르겠다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_ship", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-004", "sea",
        "파도가 비슷한 간격으로 밀려온다. 몇 번 세어보다가 그만뒀다. 정확히 재려면 시계가 필요하다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_wave", "wave_interval", "subject:sea"), listOf("SEA-013"), "daily",
    ),
    WindowObservation(
        "SEA-005", "sea",
        "바다를 보고 있었다. 별다른 건 없었다. 굳이 뭔가를 발견해야 하는 건 아니니까.",
        listOf(), null, listOf(), null,
        "normal", listOf("subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-006", "sea",
        "수평선이 흐릿하다. 바다와 하늘의 경계가 어디인지 한참 봤다. 경계가 없는 건 아니고, 구별이 잘 안 된다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_horizon", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-007", "sea",
        "바람이 부는데 파도는 생각보다 잔잔하다. 바람이 불면 바로 파도가 커지는 건 아닌가.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_wave", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-008", "sea",
        "파도가 한꺼번에 몰려오는 것처럼 보인다. 큰 파도 몇 개가 지나가고 다시 잠잠해졌다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_wave", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-009", "sea",
        "바람은 거의 없는데 파도가 계속 들어온다. 지금 여기서 부는 바람만으로 설명할 수는 없겠다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_wave", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-010", "sea",
        "파도가 바위에 닿으면서 하얗게 부서진다. 멀리서는 그저 물결이었는데.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_wave", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-011", "sea",
        "파도가 들어올 때마다 같은 바위에서 부서진다. 바위의 위치는 그대로인데 물의 움직임이 달라진다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_wave", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-012", "sea",
        "책에서 읽은 너울이 이런 걸까. 파도 모양은 비교적 고른데, 창문에서 보는 것만으로는 확신할 수 없다.",
        listOf(Requirement("지식", "너울")), "apply", listOf("swell"), "너울·풍파",
        "interval", listOf("sea_wave", "subject:sea"), listOf(), "apply",
    ),
    WindowObservation(
        "SEA-013", "sea",
        "파도 사이의 시간을 재봤다. 이 간격을 주기라고 한다. 눈으로 대충 셀 때보다 비교하기는 편하다.",
        listOf(Requirement("지식", "파도_주기"), Requirement("경험", "파도_주기_측정")), "apply", listOf("wave_period"), "파동의 주기",
        "interval", listOf("subject:sea"), listOf(), "apply",
    ),
    WindowObservation(
        "SEA-014", "sea",
        "예전에는 파도가 크고 작은지만 봤다. 이제는 간격과 방향도 눈에 들어온다. 그렇다고 바다가 더 단순해진 건 아니다.",
        listOf(Requirement("기록", "파도_간격"), Requirement("기록", "파도_방향")), null, listOf(), null,
        "normal", listOf("subject:sea"), listOf(), "past",
    ),
    WindowObservation(
        "SEA-015", "sea",
        "바닷가 바위가 평소보다 많이 드러나 있다. 물이 줄어든 것처럼 보인다.",
        listOf(Requirement("시간", "낮")), null, listOf(), null,
        "normal", listOf("rock_exposed", "sea_tide", "tide", "subject:sea"), listOf("SEA-016"), "daily",
    ),
    WindowObservation(
        "SEA-016", "sea",
        "아까 보이던 바위가 물에 잠겼다. 바위가 움직인 건 아닐 테니 물 높이가 달라진 모양이다.",
        listOf(Requirement("기록", "바위_노출")), null, listOf(), null,
        "normal", listOf("sea_tide", "tide", "subject:sea"), listOf("SEA-019"), "past",
    ),
    WindowObservation(
        "SEA-017", "sea",
        "같은 시간에 창밖을 봤는데 어제와 물 높이가 다르다. 시간을 맞추는 것만으로는 부족한가 보다.",
        listOf(Requirement("기록", "다른날_동시간_조위")), null, listOf(), null,
        "normal", listOf("sea_tide", "tide", "subject:sea"), listOf(), "past",
    ),
    WindowObservation(
        "SEA-018", "sea",
        "물이 들어오는 것과 파도가 밀려오는 것은 다른 현상일까. 둘 다 물이 움직인다는 점에서는 같은데.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_tide", "subject:sea"), listOf("SEA-019"), "daily",
    ),
    WindowObservation(
        "SEA-019", "sea",
        "밀물과 썰물은 바닷물 높이가 오르내리는 현상이다. 파도가 밀려오는 것과는 구분해야 한다.",
        listOf(), "discover", listOf("tide_vs_wave"), "조석과 파도의 구분",
        "interval", listOf("sea_tide", "subject:sea"), listOf(), "discover",
    ),
    WindowObservation(
        "SEA-020", "sea",
        "달과 태양이 조석에 영향을 준다고 한다. 그렇다면 달의 위치만 보고 밀물 시간을 맞힐 수 있을까.",
        listOf(Requirement("지식", "조석_원리")), "apply", listOf("tide_principle"), "달·태양과 조석",
        "interval", listOf("subject:sea"), listOf(), "apply",
    ),
    WindowObservation(
        "SEA-021", "sea",
        "달이 머리 위에 있다고 반드시 이곳이 만조인 건 아니다. 해안의 형태와 바다의 조건도 봐야 한다.",
        listOf(Requirement("지식", "지역_조석")), "discover", listOf("regional_tide"), "지역별 조석의 차이",
        "interval", listOf("subject:sea"), listOf(), "discover",
    ),
    WindowObservation(
        "SEA-022", "sea",
        "전에는 바위가 드러나면 물이 빠졌다고만 생각했다. 이제는 조위가 얼마나 달라졌는지 궁금하다.",
        listOf(Requirement("기록", "조위_관찰")), null, listOf(), null,
        "normal", listOf("sea_tide", "subject:sea"), listOf(), "past",
    ),
    WindowObservation(
        "SEA-023", "sea",
        "오늘은 바다가 유난히 푸르다. 어제도 파랬는데, 같은 파란색은 아닌 것 같다.",
        listOf(Requirement("시간", "낮")), null, listOf(), null,
        "normal", listOf("sea_color", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-024", "sea",
        "구름이 지나가자 바다 한쪽이 어두워졌다. 물 자체가 변한 건 아닌 것 같다.",
        listOf(Requirement("시간", "낮")), null, listOf(), null,
        "normal", listOf("sea_color", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-025", "sea",
        "바닷물이 갈색으로 보인다. 흙이 섞인 걸까. 가까이 가서 보기 전에는 모르겠다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_color", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-026", "sea",
        "햇빛이 비치는 각도에 따라 물빛이 달라진다. 바다 색을 하나로 정하는 건 생각보다 어렵다.",
        listOf(Requirement("시간", "낮")), null, listOf(), null,
        "normal", listOf("sea_color", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-027", "sea",
        "바다의 색은 물속에서 빛이 흡수되고 산란되는 방식에 영향을 받는다. 그런데 지금 보이는 색에는 하늘의 반사도 섞여 있다.",
        listOf(), "discover", listOf("ocean_optics"), "해양광학: 흡수·산란·반사",
        "interval", listOf("sea_color", "subject:sea"), listOf(), "discover",
    ),
    WindowObservation(
        "SEA-028", "sea",
        "물이 탁하다고 반드시 오염된 건 아니다. 부유물이 많아도 탁해질 수 있다. 색만 보고 수질을 판단하려던 생각은 보류했다.",
        listOf(), "discover", listOf("turbidity"), "탁도와 부유물",
        "interval", listOf("sea_color", "subject:sea"), listOf(), "discover",
    ),
    WindowObservation(
        "SEA-029", "sea",
        "배 한 척이 바다를 가로지른다. 어디로 가는지는 모르겠다. 목적지가 있다는 건 거의 확실하겠지만.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_ship", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-030", "sea",
        "배가 지나간 자리에 물결이 길게 남았다. 배는 이미 멀어졌는데 흔적은 아직 움직이고 있다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_ship", "subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-031", "sea",
        "바다에 떠 있는 부표가 조금씩 흔들린다. 저건 왜 저 자리에 있는 걸까. 그냥 떠다니는 물건은 아닌 것 같다.",
        listOf(), null, listOf(), null,
        "normal", listOf("sea_ship", "subject:sea"), listOf("SEA-032"), "daily",
    ),
    WindowObservation(
        "SEA-032", "sea",
        "부표는 종류에 따라 항로와 위험 구역 등을 알리는 역할을 한다. 하지만 저 부표가 무엇을 뜻하는지는 아직 모르겠다.",
        listOf(), "discover", listOf("navigation_marks"), "부표·항로표지",
        "interval", listOf("sea_ship", "subject:sea"), listOf(), "discover",
    ),
    WindowObservation(
        "SEA-033", "sea",
        "배가 돌아가는 것처럼 보인다. 가장 짧은 길이 항상 지나갈 수 있는 길은 아닐 것이다. 바다에도 길이 있다는 말이 조금 이해된다.",
        listOf(Requirement("지식", "항로표지")), "apply", listOf("navigation"), "항로와 항해",
        "interval", listOf("sea_ship", "subject:sea"), listOf(), "apply",
    ),
    WindowObservation(
        "SEA-034", "sea",
        "예전에는 바다가 늘 비슷해 보였다. 요즘은 볼 때마다 다른 게 눈에 들어온다. 바다가 달라진 건 아닐 텐데.",
        listOf(Requirement("기록", "서로다른_해양관찰_3종")), null, listOf(), null,
        "interval", listOf("subject:sea"), listOf(), "memory",
    ),
    WindowObservation(
        "SEA-035", "sea",
        "저번에 궁금했던 파도의 방향을 다시 살펴봤다. 오늘은 바람이 달라서 비교하기가 어렵다. 같은 조건을 찾는 것부터 일이네.",
        listOf(Requirement("기록", "파도_방향"), Requirement("질문", "파도_방향")), null, listOf(), null,
        "interval", listOf("subject:sea"), listOf(), "past",
    ),
    WindowObservation(
        "SEA-036", "sea",
        "물이 빠졌을 때 드러나는 바위를 이제는 알아볼 수 있다. 바위가 익숙해진 건지, 물 높이를 보는 습관이 생긴 건지 모르겠다.",
        listOf(Requirement("기록", "조위_관찰")), null, listOf(), null,
        "normal", listOf("sea_tide", "subject:sea"), listOf(), "past",
    ),
    WindowObservation(
        "SEA-037", "sea",
        "배가 지나간 뒤 남은 물결을 보고 있었다. 파도와 항적, 바람과 너울. 이름을 알게 되니 구별할 게 늘었다.",
        listOf(Requirement("지식", "파도_종류"), Requirement("지식", "선박_항적")), null, listOf(), null,
        "normal", listOf("subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SEA-038", "sea",
        "바다를 한참 바라봤다. 그러다 배가 고파졌다. 무슨 생각을 하고 있었는지는 잊었다.",
        listOf(), null, listOf(), null,
        "normal", listOf("subject:sea"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-001", "sky",
        "구름이 천천히 지나간다. 움직이는 건 구름인데, 잠깐 하늘 전체가 밀려가는 것처럼 보였다.",
        listOf(), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-002", "sky",
        "하늘이 맑다. 창문을 열었다. 바람이 생각보다 차서 다시 닫았다.",
        listOf(), null, listOf(), null,
        "normal", listOf("subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-003", "sky",
        "구름이 길게 늘어져 있다. 처음부터 저 모양이었는지, 움직이면서 변한 건지 모르겠다. 처음부터 봤어야 했다.",
        listOf(), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-004", "sky",
        "해가 기울면서 방 안의 그림자가 길어졌다. 시계를 안 봐도 시간이 흐른다는 건 알 수 있다. 정확히 몇 시인지는 모르지만.",
        listOf(Requirement("시간", "해질녘")), null, listOf(), null,
        "normal", listOf("shadow", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-005", "sky",
        "달이 보인다. 어제도 비슷한 곳에 있었던 것 같다. 어제 몇 시에 봤는지부터 기억이 안 난다. 비교 실패.",
        listOf(Requirement("기록", "전날_달_관찰")), null, listOf(), null,
        "normal", listOf("moon", "subject:sky"), listOf(), "past",
    ),
    WindowObservation(
        "SKY-006", "sky",
        "하늘이 어두워지고 있다. 낮도 밤도 아닌 것 같다. 사실 낮과 밤 사이에 이름을 붙인다고 경계가 생기는 건 아니다.",
        listOf(Requirement("시간", "해질녘")), null, listOf(), null,
        "normal", listOf("subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-007", "sky",
        "구름이 빠르게 지나간다. 바람이 꽤 부는 모양이다. 여기서 느껴지는 바람도 같은 방향일까.",
        listOf(), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-008", "sky",
        "하늘에 작은 구름들이 듬성듬성 떠 있다. 하나씩 세다가 어디까지 셌는지 잊었다. 처음부터 다시 세기는 싫다.",
        listOf(), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-009", "sky",
        "구름 아래쪽이 유난히 어둡다. 비가 오려는 걸까. 구름 색만 보고 판단하기엔 근거가 부족하다.",
        listOf(), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-010", "sky",
        "높은 곳의 구름과 낮은 곳의 구름이 서로 다른 방향으로 움직이는 것 같다. 착시일 수도 있으니 조금 더 봐야겠다.",
        listOf(), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-011", "sky",
        "구름의 모양이 조금씩 변한다. 아까는 동물처럼 생겼다고 생각했는데, 지금은 그냥 구름이다.",
        listOf(), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-012", "sky",
        "적운은 대류에 의해 발달하는 구름이다. 저 구름도 아래쪽은 비교적 평평하고 위로 부풀어 있다.",
        listOf(), "discover", listOf("cumulus_convection"), "적운과 대류",
        "interval", listOf("cloud", "subject:sky"), listOf(), "discover",
    ),
    WindowObservation(
        "SKY-013", "sky",
        "권운은 높은 고도에서 주로 얼음 결정으로 이루어진다. 얇게 퍼진 저 구름도 권운일까. 멀어서 확신은 어렵다.",
        listOf(), "discover", listOf("cirrus_ice"), "권운과 얼음 결정",
        "interval", listOf("cloud", "subject:sky"), listOf(), "discover",
    ),
    WindowObservation(
        "SKY-014", "sky",
        "구름이 움직이는 방향과 지상에서 부는 바람이 꼭 같지는 않다. 고도에 따라 바람이 달라질 수 있으니까.",
        listOf(Requirement("지식", "고도별_바람")), "apply", listOf("wind_by_altitude"), "고도별 바람",
        "interval", listOf("subject:sky"), listOf(), "apply",
    ),
    WindowObservation(
        "SKY-015", "sky",
        "하늘이 붉게 물들었다. 주황색에 가까운 부분도 있고 분홍색에 가까운 부분도 있다. 어디서부터 다른 색이라고 해야 할지 모르겠다.",
        listOf(Requirement("시간", "해질녘")), null, listOf(), null,
        "normal", listOf("sunset_view", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-016", "sky",
        "건물에 가려 해가 보이지 않는다. 조금 높은 곳에 올라가면 아직 보일까.",
        listOf(), null, listOf(), null,
        "normal", listOf("subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-017", "sky",
        "구름 가장자리가 밝게 빛난다. 같은 구름인데 어느 쪽은 어둡고 어느 쪽은 밝다. 빛이 닿는 방향 때문일까.",
        listOf(), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-018", "sky",
        "해가 낮게 떠 있을 때는 햇빛이 대기 속을 더 긴 경로로 지나간다. 짧은 파장의 빛이 상대적으로 많이 산란되면서 붉은빛이 두드러진다.",
        listOf(Requirement("시간", "해질녘")), "discover", listOf("rayleigh_sunset"), "레이리 산란·일몰",
        "interval", listOf("sunset_view", "subject:sky"), listOf(), "discover",
    ),
    WindowObservation(
        "SKY-019", "sky",
        "방 안으로 들어온 햇빛이 바닥에 길게 누워 있다. 조금 전보다 더 안쪽까지 들어왔다. 해가 움직인다는 표현은 편하지만 정확히는 지구가 돌고 있다.",
        listOf(Requirement("시간", "해질녘")), null, listOf(), null,
        "normal", listOf("shadow", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-020", "sky",
        "해질녘이 붉다고 내일 날씨가 반드시 좋은 건 아니다. 대기의 상태를 짐작할 단서는 될 수 있지만, 색 하나로 예보하기에는 부족하다.",
        listOf(Requirement("시간", "해질녘")), "discover", listOf("sky_color_forecast_limits"), "대기광학과 날씨 예측 한계",
        "interval", listOf("sunset_view", "subject:sky"), listOf(), "discover",
    ),
    WindowObservation(
        "SKY-021", "sky",
        "대낮인데 달이 떠 있다. 밤에만 보이는 줄 알았는데, 생각해 보니 누가 그런 규칙을 정한 적은 없다.",
        listOf(), null, listOf(), null,
        "normal", listOf("moon", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-022", "sky",
        "달이 어제보다 조금 다른 모양이다. 구름에 가린 건 아닌 것 같다.",
        listOf(Requirement("기록", "이전날_달_관찰")), null, listOf(), null,
        "normal", listOf("moon", "subject:sky"), listOf(), "past",
    ),
    WindowObservation(
        "SKY-023", "sky",
        "달이 반쯤 보인다. 정확히 반인지 확인하고 싶지만, 눈으로는 그 정도까지 구별하기 어렵다.",
        listOf(), null, listOf(), null,
        "normal", listOf("moon", "subject:sky"), listOf("SKY-024"), "daily",
    ),
    WindowObservation(
        "SKY-024", "sky",
        "달의 위상은 태양·지구·달의 상대적인 위치에 따라 달라진다. 달이 실제로 깎이는 건 아니다.",
        listOf(), "discover", listOf("moon_phase"), "달의 위상",
        "interval", listOf("subject:sky"), listOf(), "discover",
    ),
    WindowObservation(
        "SKY-025", "sky",
        "별이 몇 개 보인다. 조금 더 어두운 곳에 가면 더 많이 보일까.",
        listOf(Requirement("시간", "밤")), null, listOf(), null,
        "normal", listOf("stars", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-026", "sky",
        "별 하나가 유난히 밝다. 가까워서 밝은 건지, 원래 밝은 별인 건지 알 수 없다.",
        listOf(Requirement("시간", "밤")), null, listOf(), null,
        "normal", listOf("stars", "subject:sky"), listOf("SKY-027"), "daily",
    ),
    WindowObservation(
        "SKY-027", "sky",
        "별의 겉보기 밝기는 거리뿐 아니라 실제 광도에도 영향을 받는다. 밝게 보인다는 이유만으로 가까운 별이라고 할 수는 없다.",
        listOf(), "discover", listOf("star_brightness"), "겉보기 밝기·광도·거리",
        "interval", listOf("subject:sky"), listOf(), "discover",
    ),
    WindowObservation(
        "SKY-028", "sky",
        "별의 위치를 기억해 두려고 했다. 그런데 주변에 기준으로 삼을 만한 게 없다. 내일도 같은 자리에서 보면 비교할 수 있을까.",
        listOf(Requirement("시간", "밤")), null, listOf(), null,
        "normal", listOf("stars", "subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SKY-029", "sky",
        "저 구름은 지난번에 봤던 것과 비슷하다. 모양은 닮았는데 높이는 잘 모르겠다. 사진이라도 찍어둘 걸 그랬나.",
        listOf(Requirement("기록", "이전_구름_관찰")), null, listOf(), null,
        "normal", listOf("cloud", "subject:sky"), listOf(), "past",
    ),
    WindowObservation(
        "SKY-030", "sky",
        "달이 지난번보다 조금 늦게 떠 있다. 같은 시간에 봤으니 비교는 가능하다. 그런데 며칠 차이였더라.",
        listOf(Requirement("기록", "서로다른날_같은시각_달_관찰")), null, listOf(), null,
        "normal", listOf("moon", "subject:sky"), listOf(), "past",
    ),
    WindowObservation(
        "SKY-031", "sky",
        "해가 지는 위치가 조금씩 달라진다. 지구 자전축이 기울어진 채 태양 주위를 공전하기 때문이다. 알고 나니 건물 모서리가 제법 쓸 만한 기준점이다.",
        listOf(Requirement("기록", "이전_일몰위치"), Requirement("지식", "계절별_일몰"), Requirement("시간", "해질녘")), "apply", listOf("axial_tilt_orbit"), "지구 자전축 기울기·공전",
        "interval", listOf("subject:sky"), listOf(), "apply",
    ),
    WindowObservation(
        "SKY-032", "sky",
        "예전에는 구름이 많으면 비가 올 것 같았다. 지금은 구름 종류부터 확인한다. 그래도 우산을 가져갈지는 여전히 고민이다.",
        listOf(Requirement("지식", "구름_분류")), "apply", listOf("cloud_classification"), "구름 분류·강수",
        "interval", listOf("subject:sky"), listOf(), "apply",
    ),
    WindowObservation(
        "SKY-033", "sky",
        "별의 위치를 비교하려고 창밖을 봤다. 오늘은 구름이 많다. 관측 실패.",
        listOf(Requirement("기록", "이전_별_관측"), Requirement("시간", "밤")), null, listOf(), null,
        "normal", listOf("subject:sky"), listOf(), "past",
    ),
    WindowObservation(
        "SKY-034", "sky",
        "하늘을 한참 보고 있었다. 그러다 목이 아파서 고개를 내렸다. 생각보다 오래 올려다본 모양이다.",
        listOf(), null, listOf(), null,
        "normal", listOf("subject:sky"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-001", "shop",
        "횟집 아저씨가 가게 앞을 쓸고 있다. 어제도 비슷한 시간에 봤다. 매일 하는 일인지, 내가 볼 때만 하는 일인지는 모르겠다.",
        listOf(Requirement("기록", "이전날_횟집청소")), null, listOf(), null,
        "normal", listOf("owner", "shop_cleaning", "subject:shop"), listOf(), "past",
    ),
    WindowObservation(
        "SHOP-002", "shop",
        "횟집 앞에 상자가 몇 개 쌓여 있다. 아까보다 줄어든 것 같다. 처음에 몇 개였는지는 세어두지 않았다.",
        listOf(Requirement("기록", "이전_상자_관찰")), null, listOf(), null,
        "normal", listOf("shop_boxes", "subject:shop"), listOf(), "past",
    ),
    WindowObservation(
        "SHOP-003", "shop",
        "횟집에 불이 켜졌다. 아직 손님은 없다. 영업을 시작한 건지 준비 중인 건지는 모르겠다.",
        listOf(), null, listOf(), null,
        "normal", listOf("subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-004", "shop",
        "손님 두 명이 횟집 앞에서 이야기를 나누다가 들어갔다. 메뉴를 정한 걸까. 아니면 그냥 문 앞에서 하던 이야기를 끝낸 걸까.",
        listOf(), null, listOf(), null,
        "normal", listOf("subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-005", "shop",
        "아저씨가 가게 밖으로 나왔다가 다시 들어간다. 뭘 하려던 건지 모르겠다. 나도 가끔 방을 나왔다가 이유를 잊는다.",
        listOf(), null, listOf(), null,
        "normal", listOf("owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-006", "shop",
        "횟집 간판 불이 꺼졌다. 오늘 장사는 끝난 모양이다. 안쪽 불은 아직 켜져 있다.",
        listOf(Requirement("시간", "밤")), null, listOf(), null,
        "normal", listOf("subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-007", "shop",
        "횟집 아저씨가 가게 앞에 물을 뿌린다. 청소를 하는 건지, 먼지가 날리지 않게 하려는 건지 모르겠다. 둘 다일 수도 있고.",
        listOf(), null, listOf(), null,
        "normal", listOf("owner", "shop_cleaning", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-008", "shop",
        "횟집 앞에 상자가 잔뜩 쌓였다. 잠시 뒤 아저씨가 하나씩 안으로 옮긴다. 저걸 다 옮기고 나면 또 다른 일이 기다리고 있겠지.",
        listOf(), null, listOf(), null,
        "normal", listOf("fish_delivery", "owner", "shop_boxes", "subject:shop"), listOf("SHOP-015"), "daily",
    ),
    WindowObservation(
        "SHOP-009", "shop",
        "소녀가 가게 앞에 나와 한참 서 있다. 누군가를 기다리는 것 같기도 하고, 그냥 바람을 쐬는 것 같기도 하다.",
        listOf(), null, listOf(), null,
        "normal", listOf("girl", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-010", "shop",
        "아저씨가 간판 불을 켰다가 다시 끈다. 고장인가 싶었는데 잠시 뒤 다시 켜졌다.",
        listOf(), null, listOf(), null,
        "interval", listOf("owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-011", "shop",
        "손님이 들어갈 때마다 가게 문이 열린다. 문이 닫히기도 전에 다음 손님이 들어갔다. 오늘은 꽤 바쁜 모양이다.",
        listOf(), null, listOf(), null,
        "normal", listOf("subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-012", "shop",
        "소녀가 강아지집 앞에 쪼그려 앉아 있다. 강아지가 고개를 들자 뭔가 말을 건넨다. 여기서는 들리지 않는다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "girl", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-013", "shop",
        "아저씨가 가게 앞에 빈 상자를 차곡차곡 쌓는다. 들어올 때는 가득 차 있었을 텐데, 나갈 때는 접어서 나가는구나.",
        listOf(), null, listOf(), null,
        "normal", listOf("owner", "shop_boxes", "subject:shop"), listOf("SHOP-017"), "daily",
    ),
    WindowObservation(
        "SHOP-014", "shop",
        "횟집 불이 꺼진 뒤에도 아저씨가 한참 가게 앞에 서 있다. 오늘 장사가 어땠는지는 모르겠지만, 퇴근이 늦어지는 건 확실해 보인다.",
        listOf(Requirement("시간", "밤")), null, listOf(), null,
        "normal", listOf("owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-015", "shop",
        "횟집 앞에 생선 상자가 도착했다. 아저씨가 곧바로 안으로 옮긴다. 무슨 생선인지는 여기서 보이지 않는다.",
        listOf(), null, listOf(), null,
        "normal", listOf("fish_box_in", "fish_delivery", "owner", "shop_boxes", "subject:shop"), listOf("SHOP-020"), "daily",
    ),
    WindowObservation(
        "SHOP-016", "shop",
        "아저씨가 생선이 담긴 상자를 옮기다가 잠깐 멈췄다. 무거워 보인다. 생선이 무거운 건지 상자가 무거운 건지는 모르겠다.",
        listOf(), null, listOf(), null,
        "normal", listOf("fish_box_in", "fish_delivery", "owner", "shop_boxes", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-017", "shop",
        "생선을 들여온 뒤 아저씨가 빈 상자를 정리한다. 입고할 때보다 정리하는 시간이 더 길어 보인다.",
        listOf(Requirement("기록", "이전_입고_작업")), null, listOf(), null,
        "normal", listOf("owner", "subject:shop"), listOf(), "past",
    ),
    WindowObservation(
        "SHOP-018", "shop",
        "아저씨가 무거운 생선 상자를 익숙하게 들어 올린다. 힘이 세서 쉬워 보이는 걸까, 오래 하다 보니 힘을 덜 쓰는 방법을 익힌 걸까.",
        listOf(), null, listOf(), null,
        "normal", listOf("fish_box_in", "fish_delivery", "owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-019", "shop",
        "생선은 잡은 뒤에도 효소 작용과 미생물에 의해 품질이 변한다. 냉장 보관은 그 속도를 늦추는 방법이지, 변화를 멈추는 방법은 아니다.",
        listOf(), "discover", listOf("fish_postmortem"), "생선 사후변화·냉장 관리",
        "interval", listOf("subject:shop"), listOf("SHOP-021"), "discover",
    ),
    WindowObservation(
        "SHOP-020", "shop",
        "생선 상자가 들어온 지 한참 됐다. 냉장고에 넣었을까. 밖에서 보이는 건 상자가 사라졌다는 사실뿐이다.",
        listOf(Requirement("기록", "이전_생선상자_입고")), null, listOf(), null,
        "normal", listOf("subject:shop"), listOf(), "past",
    ),
    WindowObservation(
        "SHOP-021", "shop",
        "생선을 숙성시키면 단백질과 ATP 관련 물질이 분해되면서 맛과 식감이 변한다. 다만 오래 두는 것과 잘 숙성시키는 것은 다르다.",
        listOf(), "discover", listOf("fish_aging"), "숙성·ATP 분해·식감",
        "interval", listOf("subject:shop"), listOf("SHOP-022"), "discover",
    ),
    WindowObservation(
        "SHOP-022", "shop",
        "같은 생선이라도 어획 이후 처리와 보관 조건에 따라 품질이 달라진다. 결국 생선 이름만 알아서는 맛을 예상하기 어렵다.",
        listOf(), "discover", listOf("fish_handling_quality"), "어획 후 처리·품질",
        "interval", listOf("subject:shop"), listOf(), "discover",
    ),
    WindowObservation(
        "SHOP-023", "shop",
        "아저씨가 소녀에게 뭔가 말을 한다. 소녀는 고개를 끄덕이더니 가게 안으로 들어갔다. 부탁을 받은 걸까. 혼난 사람의 걸음걸이는 아닌 것 같다.",
        listOf(), null, listOf(), null,
        "normal", listOf("girl", "owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-024", "shop",
        "아저씨가 강아지 앞에 쪼그려 앉았다. 강아지가 꼬리를 흔들자 아저씨도 웃는다. 무슨 대화를 나누는 건지, 저 둘만 아는 것 같다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_owner_reaction", "dog_seen", "owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-025", "shop",
        "손님 한 명이 가게를 나서다가 다시 돌아왔다. 아저씨와 몇 마디 나누더니 웃으면서 떠난다. 두고 간 물건이 있었던 걸까. 손에는 아무것도 늘어나지 않았다.",
        listOf(), null, listOf(), null,
        "interval", listOf("owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-026", "shop",
        "소녀가 가게 앞 계단에 앉아 발끝으로 바닥을 두드린다. 일정한 박자인 것 같다가도 중간에 멈춘다. 무슨 노래라도 떠올리고 있는 걸까.",
        listOf(), null, listOf(), null,
        "normal", listOf("girl", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-027", "shop",
        "강아지가 아저씨를 따라가다가 소녀 쪽으로 방향을 바꾼다. 소녀가 손을 내밀자 곧장 달려갔다. 처음부터 저쪽으로 가고 싶었던 걸까.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "girl", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-028", "shop",
        "처음 보는 사람이 가게 앞에서 한참 간판을 올려다본다. 들어갈까 말까 고민하는 줄 알았는데 사진을 찍고 떠났다. 가게를 찾은 게 아니었나 보다.",
        listOf(), null, listOf(), null,
        "interval", listOf("subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-029", "shop",
        "아저씨가 가게 앞 의자에 앉아 있다. 한동안 아무것도 하지 않는다. 쉬고 있는 사람을 보고 있으니 나도 아무것도 안 하고 있다는 사실이 떠올랐다.",
        listOf(), null, listOf(), null,
        "normal", listOf("owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-030", "shop",
        "가게 앞에서 무언가 떨어지는 소리가 났다. 아저씨와 소녀가 동시에 밖으로 나온다. 잠시 뒤 둘 다 웃는다. 뭔지는 못 봤지만 큰일은 아닌 모양이다.",
        listOf(), null, listOf(), null,
        "interval", listOf("girl", "owner", "subject:shop"), listOf(), "daily",
    ),
    WindowObservation(
        "SHOP-031", "shop",
        "횟집 아저씨가 오늘도 가게 앞을 쓸고 있다. 매일 같은 일을 하는 것 같은데, 자세히 보면 시작하는 시간도 끝내는 순서도 조금씩 다르다. 같은 일이라고 부를 수 있는 범위는 어디까지일까.",
        listOf(Requirement("기록", "횟집청소_반복")), null, listOf(), null,
        "interval", listOf("owner", "shop_cleaning", "subject:shop"), listOf(), "past",
    ),
    WindowObservation(
        "SHOP-032", "shop",
        "처음에는 소녀가 가게 앞에 서 있는 모습만 눈에 들어왔다. 요즘은 소녀가 밖으로 나올 때마다 강아지가 먼저 반응하는 것도 보인다. 전에도 그랬는데 내가 몰랐던 걸까.",
        listOf(Requirement("기록", "소녀_관찰"), Requirement("기록", "강아지_관찰")), null, listOf(), null,
        "interval", listOf("dog_seen", "girl", "subject:shop"), listOf(), "past",
    ),
    WindowObservation(
        "SHOP-033", "shop",
        "이 시간이면 횟집 앞에 상자가 쌓여 있을 줄 알았다. 오늘은 아무것도 없다. 규칙을 발견했다고 생각하면 꼭 이런 날이 생긴다.",
        listOf(Requirement("기록", "상자_입고_반복")), null, listOf(), null,
        "interval", listOf("subject:shop"), listOf(), "past",
    ),
    WindowObservation(
        "SHOP-034", "shop",
        "횟집 앞에서 아저씨와 소녀가 이야기를 나누고 있다. 예전에는 누가 누구인지도 몰랐는데, 이제는 멀리서도 알아본다. 언제부터 익숙해졌는지는 모르겠다.",
        listOf(Requirement("기록", "아저씨_관찰"), Requirement("기록", "소녀_관찰")), null, listOf(), null,
        "interval", listOf("girl", "owner", "subject:shop"), listOf(), "past",
    ),
    WindowObservation(
        "SHOP-035", "shop",
        "횟집을 한참 바라봤다. 아저씨는 가게 안으로 들어갔고, 소녀는 강아지 옆에 앉아 있다. 저 사람들은 내가 여기서 보고 있다는 사실을 모를 것이다. 생각해 보니 나도 저 사람들이 보는 풍경은 모른다.",
        listOf(Requirement("기록", "횟집_장기관찰")), null, listOf(), null,
        "interval", listOf("dog_seen", "girl", "owner", "subject:shop"), listOf(), "memory",
    ),
    WindowObservation(
        "DOG-001", "dog",
        "횟집 앞 강아지가 엎드려 있다. 자는 줄 알았는데 귀가 움직였다. 눈을 감고 있어도 주변 소리는 듣고 있는 모양이다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-002", "dog",
        "강아지가 앞발을 쭉 뻗고 기지개를 켠다. 뒷다리까지 펴더니 다시 원래 자리에 앉았다. 준비운동만 하고 운동은 안 하는 건가.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-003", "dog",
        "강아지가 자기 꼬리를 잠깐 쳐다본다. 한 바퀴 돌더니 관심을 잃었다. 방금 뭘 확인하려던 걸까.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-004", "dog",
        "강아지가 바닥에 코를 대고 한참 냄새를 맡는다. 내가 보기엔 아무것도 없는데, 저 녀석한테는 뭔가 있는 모양이다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf("DOG-018"), "daily",
    ),
    WindowObservation(
        "DOG-005", "dog",
        "강아지가 가게 앞을 지나가는 사람을 바라본다. 사람이 멀어지자 다시 엎드린다. 일일이 확인하는 것도 꽤 귀찮을 것 같다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_passerby_reaction", "dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-006", "dog",
        "강아지가 자기 집에 머리만 넣었다가 다시 나온다. 안에 뭔가 있는 걸까. 아니면 들어갈지 말지 고민한 걸까.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-007", "dog",
        "강아지가 앞발로 귀 뒤를 긁는다. 몇 번 긁더니 발을 내려놓고 가만히 앉아 있다. 가려움이 해결됐는지는 알 수 없다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-008", "dog",
        "강아지가 햇볕이 드는 곳에 누웠다. 잠시 뒤 그늘로 옮겨간다. 따뜻한 건 좋지만 너무 더운 건 싫은 모양이다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "sunny_spot", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-009", "dog",
        "비가 내리자 강아지가 자기 집으로 들어갔다. 머리만 밖으로 내놓고 빗줄기를 구경한다. 비를 피하는 건지, 구경하는 건지. 둘 다 가능하겠다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-010", "dog",
        "바람이 불자 강아지의 털이 한쪽으로 쏠린다. 강아지는 별 반응이 없다. 저 정도 바람은 신경 쓸 일도 아닌가 보다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-011", "dog",
        "강아지가 햇볕이 드는 자리를 따라 조금씩 움직인다. 해시계도 아니고. 그런데 저 녀석이 있는 곳을 보면 햇볕이 어디까지 들어오는지 알 수 있겠다.",
        listOf(Requirement("기록", "이전_햇볕자리")), null, listOf(), null,
        "normal", listOf("dog_seen", "sunny_spot", "subject:dog"), listOf(), "past",
    ),
    WindowObservation(
        "DOG-012", "dog",
        "횟집 앞이 어두워지자 강아지가 자기 집으로 들어간다. 잠들려는 줄 알았는데 다시 나왔다. 잠자리를 정하는 데도 시행착오가 필요한가 보다.",
        listOf(Requirement("시간", "저녁/밤")), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-013", "dog",
        "강아지가 지나가는 오토바이를 향해 고개를 돌린다. 오토바이가 사라진 뒤에도 한동안 같은 방향을 바라본다. 소리가 아직 들리는 걸까.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf("DOG-017"), "daily",
    ),
    WindowObservation(
        "DOG-014", "dog",
        "비가 그친 뒤 강아지가 마당 여기저기를 돌아다니며 냄새를 맡는다. 비가 오기 전과 냄새가 달라졌을까. 나한테는 그냥 젖은 흙냄새인데.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf("DOG-015"), "daily",
    ),
    WindowObservation(
        "DOG-015", "dog",
        "강아지가 바람이 불어오는 쪽으로 코를 들었다. 개의 후각은 공기 중 냄새 분자를 감지한다. 바람이 냄새를 운반한다는 걸 생각하면 저 행동도 이해가 된다.",
        listOf(Requirement("지식", "개의_후각")), "apply", listOf("dog_smell"), "후각·냄새 분자 이동",
        "interval", listOf("dog_seen", "subject:dog"), listOf(), "apply",
    ),
    WindowObservation(
        "DOG-016", "dog",
        "강아지가 한참 같은 자리에 앉아 있다. 지나가는 사람도 보고, 바다 쪽도 본다. 심심하지 않은 걸까. 아니면 나보다 훨씬 많은 걸 보고 있는 걸까.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-017", "dog",
        "강아지가 갑자기 귀를 쫑긋 세운다. 고개를 조금 돌리더니 다시 반대쪽으로 돌린다. 개는 사람보다 높은 주파수의 소리까지 들을 수 있다. 나한테는 조용한데 저 녀석한테는 아닐 수도 있겠다.",
        listOf(), "discover", listOf("dog_hearing"), "개의 가청 주파수",
        "interval", listOf("dog_seen", "subject:dog"), listOf(), "discover",
    ),
    WindowObservation(
        "DOG-018", "dog",
        "강아지가 전봇대 밑에서 한참 냄새를 맡는다. 개는 냄새를 통해 다른 개의 존재와 흔적에 관한 정보를 얻을 수 있다. 저 녀석은 지금 내가 읽을 수 없는 기록을 읽고 있는 셈이다.",
        listOf(), "discover", listOf("dog_scent_marking"), "개의 냄새 표지·후각 의사소통",
        "interval", listOf("dog_seen", "subject:dog"), listOf("DOG-027"), "discover",
    ),
    WindowObservation(
        "DOG-019", "dog",
        "강아지가 꼬리를 흔든다. 반가운 건가 싶었는데, 꼬리를 흔드는 행동이 언제나 기쁨을 뜻하는 건 아니라고 한다. 몸 전체를 봐야 한다는데, 여기서는 잘 모르겠다.",
        listOf(), "discover", listOf("dog_tail_signal"), "개의 꼬리 신호",
        "interval", listOf("dog_seen", "subject:dog"), listOf(), "discover",
    ),
    WindowObservation(
        "DOG-020", "dog",
        "아저씨가 가게 문을 열자 강아지가 벌떡 일어났다. 개는 반복해서 함께 일어나는 사건 사이의 관계를 학습한다. 문이 열릴 때마다 좋은 일이 있었다면, 문 여는 소리만으로도 기대하게 될 수 있다. 나도 냉장고 문 열리는 소리에는 꽤 민감하다.",
        listOf(), "discover", listOf("associative_learning"), "연합학습·조건형성",
        "interval", listOf("dog_owner_reaction", "dog_seen", "owner", "subject:dog"), listOf(), "discover",
    ),
    WindowObservation(
        "DOG-021", "dog",
        "강아지가 입을 크게 벌리고 하품한다. 졸린 건가. 개의 하품은 피로 외에도 여러 상황에서 나타난다고 한다. 행동 하나만 보고 감정을 단정하기는 어렵다.",
        listOf(), "discover", listOf("dog_yawn"), "하품과 행동 해석",
        "interval", listOf("dog_seen", "subject:dog"), listOf(), "discover",
    ),
    WindowObservation(
        "DOG-022", "dog",
        "낯선 사람이 다가오자 강아지가 아저씨를 먼저 쳐다본다. 개는 낯선 상황에서 사람의 표정이나 목소리를 참고해 행동을 조절하기도 한다. 이를 사회적 참조라고 한다. 저 녀석도 아저씨의 반응을 확인하는 걸까.",
        listOf(Requirement("지식", "사회적_참조")), "apply", listOf("social_referencing"), "개의 사회적 참조",
        "interval", listOf("dog_seen", "owner", "subject:dog"), listOf(), "apply",
    ),
    WindowObservation(
        "DOG-023", "dog",
        "강아지가 소녀가 다가오기 전에 고개를 들었다. 개는 사람마다 다른 체취를 구별할 수 있다. 얼굴을 보지 않아도 누가 오는지 알아챌 수 있다는 뜻이다. 물론 이번에도 냄새로 알아챘는지는 모르겠다.",
        listOf(), "discover", listOf("dog_scent_identification"), "체취에 의한 사람 구별",
        "interval", listOf("dog_seen", "girl", "subject:dog"), listOf(), "discover",
    ),
    WindowObservation(
        "DOG-024", "dog",
        "강아지가 가만히 앉아 바다를 바라본다. 나도 같은 방향을 본다. 같은 풍경을 보고 있지만, 같은 것을 경험하고 있다고 말할 수 있을까.",
        listOf(), null, listOf(), null,
        "interval", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-025", "dog",
        "횟집 아저씨가 밖으로 나오자 강아지가 고개를 든다. 다른 사람이 지나갈 때는 가만히 있더니. 사람을 구별하고 있는 건 확실한 것 같다.",
        listOf(Requirement("기록", "강아지_행인반응"), Requirement("기록", "강아지_아저씨반응")), null, listOf(), null,
        "normal", listOf("dog_seen", "owner", "subject:dog"), listOf(), "past",
    ),
    WindowObservation(
        "DOG-026", "dog",
        "소녀가 강아지 옆에 앉자 강아지가 몸을 바짝 붙인다. 소녀는 별다른 반응 없이 계속 앉아 있다. 둘 다 익숙한 일인 모양이다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "girl", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-027", "dog",
        "강아지가 아저씨가 벗어둔 작업복에 코를 묻는다. 개는 냄새를 통해 익숙한 사람을 구별하고 기억할 수 있다. 저 옷은 나한테 그냥 옷인데, 저 녀석한테는 아저씨의 흔적이겠구나.",
        listOf(), "discover", listOf("dog_scent_memory"), "후각 기억",
        "interval", listOf("dog_seen", "owner", "subject:dog"), listOf(), "discover",
    ),
    WindowObservation(
        "DOG-028", "dog",
        "강아지가 횟집 문 앞에 앉아 있다. 문이 열릴 때마다 고개를 들지만, 나오는 사람을 확인하고는 다시 앉는다. 누군가를 기다리는 걸까. 누구인지는 조금 더 보면 알 수 있겠다.",
        listOf(), null, listOf(), null,
        "normal", listOf("dog_seen", "subject:dog"), listOf(), "daily",
    ),
    WindowObservation(
        "DOG-029", "dog",
        "처음에는 강아지가 짖을 때마다 무슨 일이 생긴 줄 알았다. 요즘은 짖는 방향부터 확인한다. 짖는 소리가 같아도 이유까지 같은 건 아닐 테니까.",
        listOf(Requirement("기록", "이전_짖음_관찰")), null, listOf(), null,
        "interval", listOf("dog_seen", "subject:dog"), listOf(), "past",
    ),
    WindowObservation(
        "DOG-030", "dog",
        "창밖을 봤는데 강아지가 보이지 않는다. 평소에는 별생각 없이 지나쳤는데, 없으니까 먼저 눈에 들어온다. 늘 있던 것이 사라지면 오히려 더 잘 보이는 모양이다.",
        listOf(Requirement("기록", "강아지_장기관찰")), null, listOf(), null,
        "interval", listOf("subject:dog"), listOf(), "memory",
    ),
    WindowObservation(
        "DOG-031", "dog",
        "강아지가 나를 올려다본다. 처음에는 내가 저 녀석을 관찰한다고 생각했는데, 저 녀석도 나를 보고 있었을지 모른다. 어느 쪽이 먼저 관찰하기 시작했는지는 알 수 없다.",
        listOf(Requirement("기록", "강아지_장기관찰")), null, listOf(), null,
        "interval", listOf("dog_seen", "subject:dog"), listOf(), "memory",
    ),
    WindowObservation(
        "YARD-001", "yard",
        "앞마당을 가로질러 고양이 한 마리가 지나간다. 지난번에도 저 길로 지나갔다. 고양이는 익숙한 이동 경로를 반복해서 이용하는 경향이 있다. 저 녀석한테는 우리 집 앞마당도 매일 지나는 길목인 모양이다.",
        listOf(Requirement("기록", "이전_고양이_관찰")), "discover", listOf("cat_spatial_memory"), "고양이의 공간 기억",
        "interval", listOf("cat", "subject:yard"), listOf(), "discover",
    ),
    WindowObservation(
        "YARD-002", "yard",
        "개미들이 마당 한쪽을 줄지어 지나간다. 개미는 먹이를 발견하면 페로몬으로 이동 경로를 표시하는 종이 많다. 뒤따르는 개미들은 그 화학적 흔적을 따라간다. 길 안내를 냄새로 하는 셈이다.",
        listOf(), "discover", listOf("ant_pheromone"), "개미 페로몬 의사소통",
        "interval", listOf("ants", "subject:yard"), listOf("YARD-010"), "discover",
    ),
    WindowObservation(
        "YARD-003", "yard",
        "마당에 드리운 그림자가 아까보다 길어졌다. 태양의 고도가 낮아질수록 같은 물체의 그림자는 길어진다. 시계가 없어도 그림자를 비교하면 시간이 흐른 걸 알 수 있다.",
        listOf(Requirement("기록", "이전_그림자_관찰")), "discover", listOf("sun_altitude_shadow"), "태양 고도·그림자",
        "interval", listOf("shadow", "subject:yard"), listOf(), "discover",
    ),
    WindowObservation(
        "YARD-004", "yard",
        "담장 아래쪽에 이끼가 자라 있다. 이끼는 뿌리 대신 헛뿌리로 몸을 고정하고, 표면을 통해 물을 흡수한다. 그래서 습기가 오래 남는 곳에서 자주 발견된다.",
        listOf(), "discover", listOf("moss"), "이끼·헛뿌리·수분 흡수",
        "interval", listOf("subject:yard"), listOf(), "discover",
    ),
    WindowObservation(
        "YARD-005", "yard",
        "작은 새 한 마리가 마당을 깡충깡충 뛰어다닌다. 새는 종과 다리 구조에 따라 걷거나 뛰거나 두 발을 모아 도약한다. 저 녀석은 걸어 다니는 것보다 뛰는 게 더 편한 모양이다.",
        listOf(), "discover", listOf("bird_locomotion"), "조류의 이동 방식",
        "interval", listOf("subject:yard"), listOf(), "discover",
    ),
    WindowObservation(
        "YARD-006", "yard",
        "담장 옆 식물이 햇빛이 들어오는 방향으로 기울어 있다. 식물의 굴광성은 빛에 반응하는 성장 조절과 관련이 있다. 줄기의 양쪽이 서로 다른 속도로 자라면서 방향이 바뀌는 것이다.",
        listOf(), "discover", listOf("phototropism"), "굴광성·차등 생장",
        "interval", listOf("plants", "subject:yard"), listOf("YARD-010"), "discover",
    ),
    WindowObservation(
        "YARD-007", "yard",
        "마당에서 흙냄새가 올라온다. 비가 내릴 때 느껴지는 특유의 흙냄새에는 토양 미생물이 만드는 지오스민 같은 물질이 관여한다. 흙냄새라고 부르지만, 실제로는 미생물의 흔적을 맡고 있는 셈이다.",
        listOf(), "discover", listOf("geosmin"), "지오스민·토양 미생물",
        "interval", listOf("subject:yard"), listOf(), "discover",
    ),
    WindowObservation(
        "YARD-008", "yard",
        "담장 구석에 거미줄이 걸려 있다. 거미줄의 모든 실이 끈적한 건 아니다. 원형 그물을 만드는 거미 중에는 끈적한 포획실과 끈적이지 않는 실을 구분해서 사용하는 종류가 있다. 자기 집에 걸리지 않으려면 구조부터 잘 짜야 하는 모양이다.",
        listOf(), "discover", listOf("spider_silk"), "거미줄의 실 종류",
        "interval", listOf("spiderweb", "subject:yard"), listOf("YARD-010"), "discover",
    ),
    WindowObservation(
        "YARD-009", "yard",
        "마당 구석에 낙엽이 쌓여 있다. 낙엽은 미생물과 작은 토양동물에 의해 분해되고, 그 안의 영양분 일부는 다시 토양으로 돌아간다. 나무에서 떨어졌다고 역할까지 끝난 건 아니다.",
        listOf(), "discover", listOf("decomposers"), "분해자·물질 순환",
        "interval", listOf("subject:yard"), listOf(), "discover",
    ),
    WindowObservation(
        "YARD-010", "yard",
        "창밖의 앞마당은 어제와 크게 다르지 않다. 그런데 개미가 지나가는 길도 있고, 식물이 자라는 방향도 있고, 거미가 만든 구조물도 있다. 아무 일도 없는 곳이라고 생각했는데, 내가 못 보고 있었던 것뿐이다.",
        listOf(Requirement("기록", "개미_관찰"), Requirement("기록", "식물_관찰"), Requirement("기록", "거미줄_관찰")), null, listOf(), null,
        "interval", listOf("subject:yard"), listOf(), "memory",
    ),
).associateBy { it.id }
