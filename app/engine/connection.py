"""
자동 연결 시스템 (자동연결시스템_개발명세서_261012).

창문 관찰로 문장 하나가 정해지고 기록된 뒤, 그 관찰을 트리거로 하는 연결 정의를 찾아
선행 경험·상황·지식 단계·중복/발전 조건을 모두 확인한다. 충족되면 관찰 문장 뒤에 붙일 연결 문장을
돌려주고 state.connections 에 기록한다.

- 자동 발생(A안), 추가 턴 없음. 일반 관찰 선택 확률은 건드리지 않는다 (정해진 관찰이 나왔을 때만 판정).
- 선행 경험은 실제 플레이 기록(observation_history / 기존 세계 이벤트 Observation / knowledge.sources)에서만
  확인하고, evidence 에도 그 ID 만 남긴다. 기록이 없으면 연결하지 않는다 (허위 경험 생성 금지).
- 같은 연결 ID·단계는 한 번만 출력한다. 다음 단계는 이전 단계가 기록된 뒤 새 증거가 있을 때만 출력한다.
- 연결은 가설·탐구·메모벽·지식 단계를 바꾸지 않는다.

followUpIds 와의 관계: followUpIds 는 "이 문장 다음에 나올 수 있는 관찰" 후보 관계(콘텐츠 그래프)일 뿐이고
여기서 판정에 쓰지 않는다. 사고 연결은 아래 CONNECTIONS 에 트리거·선행 조건을 명시한 것만 발생한다.
"""
from __future__ import annotations

from . import window_content as wc
from .models import GameState

KNOWLEDGE_RANK = {"encountered": 1, "understood": 2, "applied": 3}

# 각 정의: id, stage, trigger(관찰 ID 목록), prerequisites, (선택) periods, text.
# 같은 id·stage 를 여러 정의가 공유할 수 있다 (예: C01 은 순서가 반대인 두 경로).
# prerequisites 항목:
#   {"obs": [...]}                      이번 행동 이전에 그 관찰 문장 중 하나를 실제로 봤음
#   {"thread": "night_light"}           기존 세계 이벤트 관찰(Observation)이 그 탐구에 실제로 있음
#   {"knowledge": id, "min": status}    지식을 그 단계 이상으로 갖고 있음 (encountered ≠ understood)
CONNECTIONS = [
    # C01 바람과 파도 — 비교·의심. 너울이 원인이라고 확정하지 않는다.
    {
        "id": "sea_wind_wave_contrast_v1", "case": "C01", "stage": 1,
        "trigger": ["SEA-007"],
        "prerequisites": [{"obs": ["SEA-009"]}],
        "text": "전에는 바람이 거의 없는데도 파도가 계속 들어왔다. 오늘은 바람이 부는데도 잔잔하다. "
                "지금 여기서 부는 바람만으로 파도를 설명할 수는 없겠다.",
    },
    {
        "id": "sea_wind_wave_contrast_v1", "case": "C01", "stage": 1,
        "trigger": ["SEA-009"],
        "prerequisites": [{"obs": ["SEA-007"]}],
        "text": "전에는 바람이 부는데도 파도가 잔잔했다. 바람과 파도가 꼭 같이 움직이는 건 아닌 모양이다.",
    },
    {
        "id": "sea_wind_wave_contrast_v1", "case": "C01", "stage": 2,
        "trigger": ["SEA-008"],
        "prerequisites": [],
        "text": "바람이 없는데 들어오던 파도, 바람이 부는데 잔잔하던 날. 그리고 지금은 파도가 몇 개씩 묶여서 온다. "
                "파도가 여기 바람 말고 다른 무언가에 따라 움직이는 걸지도 모른다. 아직은 짐작이다.",
    },
    # C02 개미와 강아지 — 재해석. 개미 페로몬 정보를 실제로 접했을 때만.
    {
        "id": "ant_dog_scent_v1", "case": "C02", "stage": 1,
        "trigger": ["DOG-004", "DOG-014", "DOG-018"],
        "prerequisites": [{"knowledge": "ant_pheromone", "min": "encountered"}],
        "text": "개미도 냄새로 길을 표시한다고 했지. 쓰는 방식은 다르겠지만, "
                "둘 다 냄새에서 정보를 얻는다는 점은 비슷하다.",
    },
    # C03 소녀와 강아지 — 회상·관계. 이름·관계는 단정하지 않는다.
    {
        "id": "girl_dog_familiar_v1", "case": "C03", "stage": 1,
        "trigger": ["DOG-023"],
        "prerequisites": [{"obs": ["SHOP-012", "SHOP-027", "SHOP-032", "DOG-026"]}],
        "text": "소녀가 곁에 있을 때 강아지가 반응하는 걸 전에도 본 적이 있다. 서로 익숙한 사이인 모양이다.",
    },
    # C04 밤바다 불빛과 부표 — 지식 확장. 부표와 불빛을 같은 물체로 확정하지 않는다.
    {
        "id": "night_light_buoy_v1", "case": "C04", "stage": 1,
        "trigger": ["SEA-031", "SEA-032"],
        "periods": wc.TIME_RULES["낮"],
        "prerequisites": [{"thread": "night_light"},
                          {"knowledge": "navigation_marks", "min": "encountered"}],
        "text": "저 부표를 보니 밤바다에서 깜빡이던 불빛이 떠오른다. 같은 물체인지는 모르겠지만, "
                "저런 표지에도 불빛이 달려 있을 수 있겠지.",
    },
]


def validate(defs: list[dict]) -> None:
    """T10: 실제 콘텐츠에 없는 관찰/지식/탐구 ID 를 참조하는 정의는 명시적으로 실패시킨다."""
    known_knowledge = set(wc.DISCOVERY_KNOWLEDGE.values()) | set(wc.APPLY_KNOWLEDGE.values())
    stages: dict[str, set] = {}
    for d in defs:
        for oid in d["trigger"]:
            assert oid in wc.OBSERVATIONS, f"{d['id']}: 트리거 관찰 {oid} 없음"
        for p in d["prerequisites"]:
            if "obs" in p:
                for oid in p["obs"]:
                    assert oid in wc.OBSERVATIONS, f"{d['id']}: 선행 관찰 {oid} 없음"
            elif "thread" in p:
                assert p["thread"] in ("night_light", "moon", "restaurant"), f"{d['id']}: 탐구 {p['thread']} 없음"
            elif "knowledge" in p:
                assert p["knowledge"] in known_knowledge, f"{d['id']}: 지식 {p['knowledge']} 공급원 없음"
                assert p["min"] in KNOWLEDGE_RANK, f"{d['id']}: 지식 단계 {p['min']}"
            else:
                raise AssertionError(f"{d['id']}: 알 수 없는 선행 조건 {p}")
        stages.setdefault(d["id"], set()).add(d["stage"])
    for cid, ss in stages.items():
        assert ss == set(range(1, max(ss) + 1)), f"{cid}: 단계가 1부터 연속이 아님 {sorted(ss)}"


validate(CONNECTIONS)


def _evidence(state: GameState, prereq: dict, current_id: str) -> str | None:
    """선행 조건 하나를 실제 기록으로 확인하고, 근거가 된 기록 ID 를 돌려준다 (없으면 None)."""
    if "obs" in prereq:
        for oid in prereq["obs"]:
            h = state.observation_history.get(oid)
            # 이번 행동으로 막 기록된 문장은 '이전 경험'이 아니다.
            if h and (oid != current_id or h["count"] >= 2):
                return oid
        return None
    if "thread" in prereq:
        for o in state.observations:
            if o.thread == prereq["thread"]:
                return o.id
        return None
    if "knowledge" in prereq:
        entry = state.knowledge.get(prereq["knowledge"])
        if not entry or KNOWLEDGE_RANK.get(entry["status"], 0) < KNOWLEDGE_RANK[prereq["min"]]:
            return None
        return entry["sources"][0]["id"] if entry.get("sources") else None
    return None


def check(state: GameState, obs: dict, defs: list[dict] | None = None) -> dict | None:
    """방금 기록된 관찰 obs 로 발생하는 연결을 판정·기록한다. 한 관찰에 연결은 최대 하나."""
    defs = CONNECTIONS if defs is None else defs
    for d in defs:
        if obs["id"] not in d["trigger"]:
            continue
        rec = state.connections.get(d["id"])
        if (rec["stage"] if rec else 0) != d["stage"] - 1:
            continue  # 이미 출력한 단계거나, 앞 단계가 아직 없다
        if "periods" in d and state.period not in d["periods"]:
            continue
        evidence = []
        for p in d["prerequisites"]:
            e = _evidence(state, p, obs["id"])
            if e is None:
                break
            if e not in evidence:
                evidence.append(e)
        else:
            if obs["id"] not in evidence:
                evidence.append(obs["id"])
            _record(state, d, obs["id"], evidence)
            return {"id": d["id"], "stage": d["stage"], "text": d["text"]}
    return None


def _record(state: GameState, d: dict, trigger_id: str, evidence: list[str]):
    rec = state.connections.get(d["id"])
    if rec is None:
        rec = {"first_day": state.day, "history": []}
        state.connections[d["id"]] = rec
    rec.update({"stage": d["stage"], "last_day": state.day,
                "trigger_observation_id": trigger_id, "evidence": evidence})
    rec["history"].append({"stage": d["stage"], "day": state.day, "period": state.period,
                           "trigger_observation_id": trigger_id, "evidence": evidence})
