"""
방 타일맵 + 오브젝트 정의 + 텍스트 콘텐츠.

타일맵: '#'=충돌(벽/가구), '.'=이동 가능, 그 외 한 글자는 오브젝트가 서 있는 칸(충돌, 인접한
'.' 칸에서만 상호작용 가능). 명세서 2장 "바닥과 러그는 이동 가능 영역, 가구와 벽은 충돌 영역"을 그대로 반영.
"""
from __future__ import annotations

ROOM_MAP = [
    "##############",
    "#....WW......#",
    "#K...........#",
    "#K..DC.......#",
    "#K..DC.....M.#",
    "#X...........#",
    "#X.......R...#",
    "#............#",
    "#E.........N.#",
    "#........L...#",
    "#####O########",
]
for _row in ROOM_MAP:
    assert len(_row) == len(ROOM_MAP[0]), "방 타일맵의 모든 행 길이가 같아야 함"

ROOM_W = len(ROOM_MAP[0])
ROOM_H = len(ROOM_MAP)

# 플레이어 시작 위치 (방 가운데 러그 위).
PLAYER_START = (6, 6)

# 타일 문자 -> 오브젝트 id. 명세서 2장의 "오브젝트 / 역할" 표 그대로.
OBJECTS = {
    "W": {
        "id": "window", "name": "창문", "role": "외부 세계 관찰의 중심",
        "tier": "essential",
    },
    "X": {
        "id": "boxes", "name": "이삿짐 상자", "role": "과거의 흔적과 관찰 도구 발견",
        "tier": "essential",
    },
    "D": {
        "id": "desk", "name": "책상", "role": "관찰과 정보와 가설을 비교하는 사고 공간",
        "tier": "essential",
    },
    "C": {
        "id": "computer", "name": "컴퓨터", "role": "빠르게 많은 후보와 다양한 출처를 제공",
        "tier": "essential",
    },
    "K": {
        "id": "bookshelf", "name": "책장", "role": "정리되고 편집된 지식",
        "tier": "essential",
    },
    "M": {
        "id": "mirror", "name": "거울", "role": "현재의 나를 관찰",
        "tier": "secondary",
    },
    "E": {
        "id": "bed", "name": "침대", "role": "눕고 생각하거나 연상하는 장소",
        "tier": "secondary",
    },
    "R": {
        "id": "radio", "name": "라디오", "role": "찾지 않았던 정보와 노래가 우연히 들어옴",
        "tier": "secondary",
    },
    "N": {
        "id": "memowall", "name": "메모벽", "role": "오랫동안 살아남은 중요한 질문",
        "tier": "secondary",
    },
    "L": {
        "id": "mail", "name": "우편물", "role": "외부 사람과 질문이 방 안으로 들어오는 통로",
        "tier": "secondary",
    },
    "O": {
        "id": "door", "name": "문", "role": "실내 탐구의 한계를 넘어 외부 탐구로 이동하는 경계",
        "tier": "essential",
    },
}

# 오브젝트 id -> 타일 좌표 목록 (타일맵에서 자동 계산됨, 아래 build_object_tiles 참고)


def build_object_tiles():
    tiles = {}
    for y, row in enumerate(ROOM_MAP):
        for x, ch in enumerate(row):
            if ch in OBJECTS:
                oid = OBJECTS[ch]["id"]
                tiles.setdefault(oid, []).append((x, y))
    return tiles


OBJECT_TILES = build_object_tiles()


def is_walkable(x: int, y: int) -> bool:
    if y < 0 or y >= ROOM_H or x < 0 or x >= ROOM_W:
        return False
    return ROOM_MAP[y][x] == "."


def object_at(x: int, y: int):
    if y < 0 or y >= ROOM_H or x < 0 or x >= ROOM_W:
        return None
    ch = ROOM_MAP[y][x]
    return OBJECTS[ch]["id"] if ch in OBJECTS else None


def is_adjacent_to_object(px: int, py: int, object_id: str) -> bool:
    for (ox, oy) in OBJECT_TILES.get(object_id, []):
        if abs(ox - px) + abs(oy - py) <= 1:
            return True
    return False


# ------------------------------------------------------------------ 텍스트 콘텐츠

# 0턴 "잠깐 보기"류 행동이 ZERO_TURN_REPEAT_LIMIT 를 넘으면 보여줄 문구.
ENOUGH_ALREADY = {
    "window": "아까 충분히 봤다. 딱히 달라진 건 없다.",
    "mirror": "거울은 됐다. 아까 본 얼굴 그대로다.",
    "bookshelf": "책등은 아까 다 훑어봤다.",
    "memowall": "메모벽도 방금 확인했다.",
    "radio": "라디오는 아까 들었던 소리와 비슷하다.",
}

WINDOW_GLANCE_LINES = [
    "창밖을 잠깐 내다봤다.",
    "창문 너머로 눈이 갔다.",
    "잠깐 밖을 살폈다.",
]

MIRROR_LINES = [
    "거울 속 얼굴을 잠깐 봤다. 평소와 비슷하다.",
    "머리가 좀 헝클어져 있다.",
    "딱히 할 말은 없다. 그냥 나다.",
]

BOOKSHELF_GLANCE = "책등을 훑어봤다. 낯익은 제목들이다."

RADIO_LINES = [
    ("flavor", "라디오에서 낯선 노래가 흘러나왔다. 나쁘지 않았다."),
    ("flavor", "디제이가 날씨 얘기를 했다. 흘려들었다."),
    ("flavor", "익숙한 멜로디다. 따라 흥얼거릴 뻔했다."),
]

BED_LINES = [
    "침대에 누워 천장을 봤다. 딱히 생각나는 건 없다.",
    "누워서 눈을 감았다 떴다. 방이 조용하다.",
    "잠깐 누워 있었다. 창밖 소리가 들렸다.",
]

MAIL_LINES_BEFORE = "오늘은 우편함이 비어 있다."
MAIL_LINES_FLAVOR = [
    "관리비 고지서가 와 있었다. 대수롭지 않게 치웠다.",
    "광고 전단지 한 장. 바로 접어 뒀다.",
]

# 고양이/석양 같은, 발견·질문·단서·보상이 전혀 없는 "아무 의미 없는 하루" 콘텐츠.
MEANINGLESS_WINDOW_EVENTS = {
    "cat": "앞마당에서 고양이와 눈이 마주쳤다. 한참 쳐다보다가, 고양이가 먼저 시선을 돌렸다. 이겼다.",
    "sunset": "해질녘 바다가 붉게 물들었다. 예쁘네.",
}

# ------------------------------------------------------------------ 이삿짐 상자 (순차 공개, RPG식 연출 없음)
BOX_REVEALS = [
    "상자 하나를 열었다. 옷가지뿐이다.",
    "다른 상자에는 그릇 몇 개와 신문지 뭉치가 들어 있었다.",
    "구석 상자를 열자 오래된 쌍안경이 나왔다. 손에 쥐어 보니 묵직하다.",  # <- 3번째에 쌍안경
    "작은 상자에서 접힌 지도 한 장이 나왔다. 펼쳐 보진 않고 다시 넣어뒀다.",
    "마지막 상자는 거의 비어 있다. 신문지만 가득하다.",
]
BINOCULARS_REVEAL_INDEX = 2  # 0-based, 세 번째 정리에서 쌍안경 발견

# ------------------------------------------------------------------ 책장
BOOKSHELF_GENERIC_BOOKS = [
    "오래된 소설 한 권을 꺼내 몇 장 넘겨봤다. 지금 당장 끌리진 않는다.",
    "표지가 바랜 잡지를 들춰봤다. 별 내용은 없다.",
]
# 특정 조사(thread)가 진행된 뒤에야 "눈에 들어오는" 책들.
BOOKSHELF_UNLOCKED_BOOKS = {
    "night_light": {
        "trigger_min_infos": 1,  # 컴퓨터 조사를 한 번이라도 한 뒤에야 이 책이 눈에 들어옴
        "text": "항로표지와 등대에 관한 책이 눈에 들어왔다. 예전엔 그냥 지나쳤던 책이다.",
        "info_text": "책에는 등대와 등부표, 항로표지가 야간에 어떻게 켜지고 깜빡이는지 정리돼 있다. "
                     "다만 지금 보이는 불빛이 그중 무엇인지는 책만으로는 알 수 없다.",
    },
    "moon": {
        "trigger_min_infos": 0,
        "text": "천문 관련 책이 눈에 들어왔다. 예전엔 신경 쓴 적 없는 책이다.",
        "info_text": "낮에도 달이 보일 수 있다는 내용을 발견했다. 위치와 태양빛의 각도에 따라 다르다고 한다.",
    },
}

# ------------------------------------------------------------------ 컴퓨터 검색
COMPUTER_SEARCHES = {
    "night_light": {
        "query": "바다 불빛",
        "requires_observation": True,
        "candidates": [
            "야간 조업 중인 어선의 불빛일 수 있다.",
            "선박 항해등일 수 있다 (좌우현에 따라 색이 다르다는 설명도 있다).",
            "등대 불빛일 수 있다.",
            "등부표(바다 위에 뜬 작은 표지)의 불빛일 수 있다.",
            "항로표지 전반에 대한 일반 설명도 나온다.",
        ],
    },
}

# ------------------------------------------------------------------ 가설 템플릿 (책상에서 플레이어가 고르는 선택지)
HYPOTHESIS_TEMPLATES = {
    "night_light": [
        {"status": "guess", "text": "저건 배일지도 모른다."},
        {"status": "guess", "text": "등대나 등부표 같은 고정된 불빛일지도 모른다."},
        {"status": "guess", "text": "그냥 야간 조업 중인 배일 것이다."},
    ],
}

# 재관찰 시 반례/강화 판단에 쓰이는, thread별 재관찰 반응 단계 (관찰 누적에 따라 달라짐).
NIGHT_LIGHT_REOBSERVE_STAGES = [
    "불빛이 있다.",
    "어제와 비슷하다.",
    "위치도 비슷한 것 같다.",
    "깜빡이는 간격도 봐야 하나 싶다.",
]

# 쌍안경: "맨눈으로 위치 확인 -> 쌍안경으로 확대"라는 관찰법을 익히게 한다 (능력치 증가가 아니라 관찰 방법의 확장).
# 맨눈 관찰(관찰하기)을 먼저 하지 않고 쌍안경부터 들이대면 뭘 봐야 할지 몰라 놓친다.
BINOCULARS_NEED_NAKED_EYE_FIRST = "쌍안경부터 들이댔더니 어디를 봐야 할지 모르겠다. 먼저 맨눈으로 위치부터 확인해야겠다."
# 쌍안경 첫 사용 시, 좁은 시야 때문에 한 번은 대상을 놓치는 연출 (그래도 턴은 쓴다 - 관찰법을 몸으로 배우는 과정).
BINOCULARS_FIRST_USE_FUMBLE = "쌍안경을 눈에 대자 시야가 확 좁아졌다. 방금까지 보이던 자리를 순간 놓쳤다 — 다시 천천히 맞춰봤다. "
# 쌍안경으로 확대했을 때, 맨눈 관찰로는 알 수 없던 디테일 (맨눈 관찰보다 한 단계 더 자세한 단서).
NIGHT_LIGHT_ZOOM_TEXT = "쌍안경으로 당겨서 보니 불빛이 짧게 두 번, 길게 한 번 깜빡인다. 일정한 패턴처럼 보인다. 그냥 흔들리는 불빛은 아닌 것 같다."

# 메모벽에 올라갈 "살아남은 질문" - thread 별, 단계에 따라 문구가 진화한다.
MEMO_WALL_QUESTION_STAGES = {
    "night_light": [
        "저 불빛은 뭘까?",
        "저 불빛, 매일 비슷한 자리에 있는 걸까?",
        "여기서는 각도가 거의 같다. 다른 위치에서 봐야 할 것 같다.",
    ],
}

# 메모벽에 올라가기 위한 최소 조건 (관찰 횟수, 가설 수정 횟수).
MEMO_WALL_THRESHOLD = {"observations": 2, "revisions": 1}

# 외부 관찰이 필요한 상태에 도달하기 위한 조건 (문이 [확인하러 간다]로 바뀜).
EXIT_READY_THRESHOLD = {"observations": 4, "revisions": 1}
