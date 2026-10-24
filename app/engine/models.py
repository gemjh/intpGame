"""
게임 상태 데이터 모델.

핵심 원칙(명세서 4장): 세계의 사실(world truth), 플레이어가 "실제로 관찰한" 사실(Observation),
책/컴퓨터가 제공하는 "정보"(InfoEntry), 플레이어 자신의 해석(Hypothesis)을 하나로 합치지 않고
끝까지 분리해서 저장한다. 어느 레코드도 "정답"을 자동으로 확정해주지 않는다.
"""
from __future__ import annotations

from dataclasses import dataclass, field, asdict
from typing import Optional

# 하루는 7개 시간대로 진행된다. 매일 전부 플레이하는 건 아니고, 저녁/밤 이후엔 자동으로 잠들 수 있다.
PERIODS = ["dawn", "morning", "day1", "day2", "sunset", "evening", "night"]
PERIOD_LABELS = {
    "dawn": "새벽", "morning": "아침", "day1": "낮", "day2": "오후",
    "sunset": "해질녘", "evening": "저녁", "night": "밤",
}

# 0턴 행동이 한 시간대 안에서 무한 반복되며 콘텐츠를 소모하지 않도록 거는 상한.
# 숫자 자체는 플레이어에게 보여주지 않는다 (명세서 3장).
ZERO_TURN_REPEAT_LIMIT = 3

HYPOTHESIS_STATUSES = ["guess", "possible", "likely", "near_certain", "revised", "discarded"]
HYPOTHESIS_STATUS_LABELS = {
    "guess": "추측", "possible": "가능성 있음", "likely": "유력",
    "near_certain": "거의 확실", "revised": "재검토됨", "discarded": "폐기됨",
}


@dataclass
class Observation:
    """플레이어가 '직접' 본 것. 세계에 실제로 일어난 일 중 관찰로 포착된 부분만 들어간다."""
    id: str
    thread: str
    day: int
    period: str
    text: str
    tags: dict = field(default_factory=dict)


@dataclass
class InfoEntry:
    """책/컴퓨터가 제공하는 정보. 직접 관찰이 아니라 '정보원'을 통해 얻은 지식/후보다."""
    id: str
    thread: str
    day: int
    source: str  # "book" | "computer"
    text: str


@dataclass
class HypothesisRevision:
    day: int
    period: str
    status: str
    text: str
    reason: str = ""  # 재검토/반례의 근거


@dataclass
class Hypothesis:
    """플레이어 자신의 해석. 관찰/정보와는 별개 레코드이며, 틀려도 삭제하지 않고 이력을 남긴다."""
    id: str
    thread: str
    created_day: int
    text: str
    status: str
    revisions: list = field(default_factory=list)  # list[HypothesisRevision], 오래된 것부터

    def latest_text(self) -> str:
        return self.revisions[-1].text if self.revisions else self.text


@dataclass
class Discovery:
    """능력치 상승이 아니라 '발견' 그 자체. 쌍안경 같은 물건, 이삿짐 속 단서 등."""
    id: str
    day: int
    period: str
    text: str


@dataclass
class ThreadState:
    """탐구 하나(예: 밤바다 불빛)의 진행 상태. 메모벽 생존 여부 판정에 쓰인다."""
    id: str
    observation_count: int = 0
    revision_count: int = 0
    exit_ready: bool = False  # 이 탐구가 "외부 관찰 필요" 단계에 도달했는지


@dataclass
class GameState:
    version: int = 2  # 2: 창문 관찰 이력/사실 플래그/지식 필드 추가 (v1 세이브는 그대로 로드됨)
    day: int = 1
    period: str = "morning"
    seed: int = 1

    # 세계의 "사실"과 플레이어가 "관찰한 사실"을 분리해서 기록한다.
    world_log: list = field(default_factory=list)       # 실제로 일어난 일 (세이브에는 들어가되 UI엔 노출 안 함)
    observations: list = field(default_factory=list)    # list[Observation]
    infos: list = field(default_factory=list)            # list[InfoEntry]
    hypotheses: list = field(default_factory=list)       # list[Hypothesis]
    discoveries: list = field(default_factory=list)      # list[Discovery]
    memo_wall: dict = field(default_factory=dict)        # thread_id -> 현재 생존 질문 문구
    threads: dict = field(default_factory=dict)          # thread_id -> ThreadState

    items: list = field(default_factory=list)            # 예: "쌍안경"
    books_read: list = field(default_factory=list)
    searches: list = field(default_factory=list)
    window_resolution: int = 0                            # (v1 호환용, 더 이상 쓰지 않음 — 시작부터 5개 대상 선택 가능)
    binoculars_fumbled_once: bool = False                  # 쌍안경 첫 사용: 좁은 시야 때문에 한 번 놓치는 연출을 이미 겪었는지

    zero_turn_counts: dict = field(default_factory=dict)  # "{object}|{day}|{period}" -> 횟수
    interaction_log: list = field(default_factory=list)   # 상호작용 기록 (오브젝트id, day, period)

    # ---- 창문 관찰·기록 (명세서 261011). 세이브 v1 에는 없는 필드라 기본값으로 채워진다.
    observation_history: dict = field(default_factory=dict)  # 문장ID -> {first_seen, last_seen, count}
    fact_flags: dict = field(default_factory=dict)           # 사실 플래그 -> {first, days[], seen_at[[day,period]]}
    knowledge: dict = field(default_factory=dict)            # 지식ID -> {status: encountered|understood|applied, sources[]}
    window_recent: list = field(default_factory=list)        # 최근 출력한 창문 문장 ID (반복 감소용)
    # ---- 자동 연결 (명세서 261012). 이 필드가 없는 기존 v2 세이브는 빈 이력으로 로드된다.
    connections: dict = field(default_factory=dict)          # 연결ID -> {stage, first_day, last_day, trigger_observation_id, evidence[], history[]}
    # 2·3차 범위 — 데이터 설계만 선반영 (아직 이 필드를 채우는 기능 없음)
    questions: dict = field(default_factory=dict)            # 질문ID -> {state: arisen|held|revisited}
    actions_done: dict = field(default_factory=dict)         # 탐구 행동ID -> 이력
    npc_relations: dict = field(default_factory=dict)        # 예: {"dog": {"name_known": true}}
    free_notes: list = field(default_factory=list)           # 자유 메모

    exit_unlocked: bool = False   # 문이 [확인하러 간다]로 바뀌었는지
    ended: bool = False           # Vertical Slice End 에 도달했는지

    log: list = field(default_factory=list)  # 화면에 보여줄 최근 서술 로그


def state_to_dict(s: GameState) -> dict:
    return asdict(s)


def state_from_dict(d: dict) -> GameState:
    """asdict() 로 저장된 중첩 dataclass(list[Observation] 등)를 다시 객체로 복원한다.
    단순 setattr 만 하면 observations 등이 plain dict 로 남아서 obs.text 같은 속성 접근이 깨진다."""
    s = GameState()
    for k, v in d.items():
        if not hasattr(s, k):
            continue
        if k == "observations":
            v = [Observation(**x) for x in v]
        elif k == "infos":
            v = [InfoEntry(**x) for x in v]
        elif k == "discoveries":
            v = [Discovery(**x) for x in v]
        elif k == "hypotheses":
            v = [Hypothesis(**{**x, "revisions": [HypothesisRevision(**r) for r in x.get("revisions", [])]})
                 for x in v]
        elif k == "threads":
            v = {tid: ThreadState(**t) for tid, t in v.items()}
        setattr(s, k, v)
    s.version = GameState.version  # v1 -> v2: 새 필드는 기본값(빈 이력)으로 시작한다
    return s
