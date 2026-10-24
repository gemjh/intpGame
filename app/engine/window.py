"""
창문 관찰 엔진 (명세서 261011: WIN-01~06, 3장 문장 선정, REC-01/02, KNW-01/02).

- 대상(바다/하늘/횟집/강아지/앞마당) 또는 "그냥 바라본다"를 고르면 문장 1개를 출력한다.
  턴 소비(1턴)와 시간대 갱신은 engine._advance_time (기존 규칙)에 맡긴다.
- 후보 = 출현 조건 충족 ∩ 반복 허용. 가중치는 window_content.WEIGHTS, 이미 본 문장 ×0.7,
  최근 3회 이내 ×0.2, 직전 문장은 다른 후보가 충분하면 제외.
- 출력 결과로 관찰 이력(최초/최근/횟수), 사실 플래그, 지식(접함)을 남긴다. 같은 문장을 다시
  봐도 최초 발견이나 지식을 중복 생성하지 않고, 반복 열람으로 지식 단계가 오르지 않는다.
"""
from __future__ import annotations

import random

from . import window_content as wc
from .models import GameState

ANY = "any"


def _now(state: GameState) -> dict:
    return {"day": state.day, "period": state.period}


def subject_label(state: GameState, subject: str) -> str:
    if subject == "dog" and dog_name_known(state):
        return wc.DOG_NAME
    return wc.SUBJECT_LABELS[subject]


def dog_name_known(state: GameState) -> bool:
    return bool(state.npc_relations.get("dog", {}).get("name_known"))


def render_text(state: GameState, text: str) -> str:
    """이름을 알게 된 뒤에는 '강아지'를 '두식이'로 치환한다 (두 단어 모두 모음으로 끝나 조사가 그대로 맞는다)."""
    if not dog_name_known(state):
        return text
    return text.replace("강아지집", f"{wc.DOG_NAME} 집").replace("강아지", wc.DOG_NAME)


# ------------------------------------------------------------------ 조건 판정
def _fact(state: GameState, flag: str) -> dict | None:
    return state.fact_flags.get(flag)


def _record_ok(state: GameState, key: str) -> bool:
    rule = wc.RECORD_RULES[key]
    if "flags_min" in rule:
        return sum(1 for f in rule["flags_min"] if _fact(state, f)) >= rule["n"]
    if "days" in rule:
        f = _fact(state, rule["days"])
        return bool(f) and len(f["days"]) >= rule["n"]
    (kind, flag), = rule.items()
    f = _fact(state, flag)
    if not f:
        return False
    if kind == "any":
        return True
    if kind == "today":
        return state.day in f["days"]
    if kind == "prev_day":
        return any(d < state.day for d in f["days"])
    if kind == "yesterday":
        return (state.day - 1) in f["days"]
    if kind == "same_period_other_day":
        return any(d != state.day and p == state.period for d, p in f["seen_at"])
    raise ValueError(f"알 수 없는 기록 규칙: {rule}")


def _knowledge_ok(state: GameState, key: str) -> bool:
    rule = wc.KNOWLEDGE_RULES[key]
    if "any" in rule:
        return any(k in state.knowledge for k in rule["any"])
    return bool(rule["all"]) and all(k in state.knowledge for k in rule["all"])


def requirements_met(state: GameState, obs: dict) -> bool:
    for r in obs["requirements"]:
        t, key = r["type"], r["key"]
        if t == "시간":
            if state.period not in wc.TIME_RULES[key]:
                return False
        elif t == "기록":
            if not _record_ok(state, key):
                return False
        elif t == "지식":
            if not _knowledge_ok(state, key):
                return False
        elif t == "경험":  # 실제로 한 탐구 행동만 (문장 출력 횟수로는 생기지 않는다)
            if not state.actions_done.get(wc.EXPERIENCE_RULES[key], {}).get("count"):
                return False
        elif t == "질문":  # 플레이어가 붙잡은(held/revisited) 준비된 질문만
            qid = wc.QUESTION_RULES[key]
            if qid is None or state.questions.get(qid, {}).get("state") not in ("held", "revisited"):
                return False
        else:
            return False
    return True


def repeat_allowed(state: GameState, obs: dict) -> bool:
    """간격 반복 문장은 같은 날 다시 나오지 않는다 (쿨다운 길이는 플레이테스트로 조정)."""
    if obs["repeatPolicy"] != "interval":
        return True
    h = state.observation_history.get(obs["id"])
    return not h or h["last_seen"]["day"] != state.day


def _weight(state: GameState, obs: dict) -> float:
    w = float(wc.WEIGHTS[obs["weightClass"]])
    if obs["id"] in state.observation_history:
        w *= wc.SEEN_MULT
    if obs["id"] in state.window_recent[-wc.RECENT_WINDOW:]:
        w *= wc.RECENT_MULT
    return w


def candidates(state: GameState, subject: str) -> list[dict]:
    cands = [o for o in wc.OBSERVATIONS.values()
             if o["subject"] == subject and requirements_met(state, o) and repeat_allowed(state, o)]
    last = state.window_recent[-1] if state.window_recent else None
    if last and len(cands) >= 3:  # 직전 문장은 다른 후보가 충분할 때(2개 이상) 제외
        cands = [o for o in cands if o["id"] != last]
    return cands


def _safe_fallback(state: GameState, subject: str) -> dict | None:
    """후보가 없을 때: 그 대상의 조건 없는 일반 반복 문장(안전한 반복 문장)을 먼저 찾는다."""
    safe = [o for o in wc.OBSERVATIONS.values()
            if o["subject"] == subject and not o["requirements"] and o["repeatPolicy"] == "normal"]
    return random.choice(safe) if safe else None


def pick(state: GameState, subject: str | None, rng: random.Random | None = None) -> tuple[str | None, dict | None]:
    """(실제 관찰한 대상, 문장) 을 고른다. 문장이 None 이면 중립 문장을 써야 한다."""
    rng = rng or random
    if subject in (None, ANY):
        pool = [s for s in wc.SUBJECTS if candidates(state, s)]
        if not pool:
            return None, None
        subject = rng.choice(pool)
    cands = candidates(state, subject)
    if not cands:
        return subject, _safe_fallback(state, subject)
    weights = [_weight(state, o) for o in cands]
    return subject, rng.choices(cands, weights=weights, k=1)[0]


# ------------------------------------------------------------------ 기록
def record_facts(state: GameState, flags: list[str]):
    for flag in flags:
        f = state.fact_flags.get(flag)
        if f is None:
            f = {"first": _now(state), "days": [], "seen_at": []}
            state.fact_flags[flag] = f
        if state.day not in f["days"]:
            f["days"].append(state.day)
        pair = [state.day, state.period]
        if pair not in f["seen_at"]:
            f["seen_at"].append(pair)
            if len(f["seen_at"]) > 40:
                f["seen_at"] = f["seen_at"][-40:]


def record_knowledge(state: GameState, knowledge_id: str, source: str) -> bool:
    """지식을 '접함'으로 기록한다. 이미 있으면 새 경로만 sources 에 추가하고 False (중복 획득 금지)."""
    entry = state.knowledge.get(knowledge_id)
    src = {"id": source, **_now(state)}
    if entry is None:
        state.knowledge[knowledge_id] = {"status": "encountered", "sources": [src]}
        return True
    if all(s["id"] != source for s in entry["sources"]):
        entry["sources"].append(src)
    return False


def record_observation(state: GameState, obs: dict) -> dict:
    """REC-01: 최초·최근 관찰 시점과 횟수. 반환값의 first 로 새 발견 여부를 알려준다."""
    h = state.observation_history.get(obs["id"])
    first = h is None
    if first:
        h = {"first_seen": _now(state), "last_seen": _now(state), "count": 0}
        state.observation_history[obs["id"]] = h
    h["last_seen"] = _now(state)
    h["count"] += 1
    state.window_recent.append(obs["id"])
    state.window_recent = state.window_recent[-10:]
    record_facts(state, obs["effects"])
    new_knowledge = []
    if obs["knowledgeMode"] == "discover":
        for kid in obs["knowledgeIds"]:
            if record_knowledge(state, kid, obs["id"]):
                new_knowledge.append(kid)
    return {"first_seen": first, "new_knowledge": new_knowledge}
