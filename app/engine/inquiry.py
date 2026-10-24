"""
2차 범위: 준비된 질문(QST-01, REC-04), 지식 단계(KNW-02), 파도 주기 서사형 탐구(5장), 기록 화면(REC-03).

- 탐구는 순서를 강제하지 않는다. 세기·기준점 측정·해석은 각각 독립 이력(actions_done)으로 남고,
  질문 등록 없이도, 책(지식)을 먼저 읽어도, 며칠 뒤 이어서 해도 동작한다.
- 질문은 관찰·행동으로 "떠오르고", 붙잡을지는 플레이어가 고른다. 완료/미완료 상태는 없다.
- 지식 단계는 행동으로만 오른다: 해석하면 이해함, 다른 날 다시 재서 비교하면 적용함. 반복 열람으로는 오르지 않는다.
- 해석은 기록이 뒷받침하는 정도(추측/가능성/유력)만 남기고 정답을 확정하지 않는다.
- 이 탐구는 메모벽·문 해금(ThreadState)에 관여하지 않는다 (엔딩 변경은 별도 합의 사항).
콘텐츠(문장)는 inquiry_content.py 의 초안을 쓴다.
"""
from __future__ import annotations

import math
import uuid

from . import connection, window, world
from . import inquiry_content as ic
from . import window_content as wc
from .models import (GameState, Hypothesis, HypothesisRevision, InfoEntry, HYPOTHESIS_STATUS_LABELS, PERIODS,
                     PERIOD_LABELS)

WAVE_THREAD = "wave_period"


def _now(state: GameState) -> dict:
    return {"day": state.day, "period": state.period}


# ------------------------------------------------------------------ 질문
def raise_questions(state: GameState, source: str) -> list[dict]:
    """source(창문 문장 ID 또는 act:<행동>)로 떠오르는 질문을 기록하고, 이번에 처음 떠오른 것만 돌려준다."""
    new = []
    for qid, q in ic.QUESTIONS.items():
        if source in q["sources"] and qid not in state.questions:
            state.questions[qid] = {"state": "arisen", "first": _now(state), "source": source,
                                    "history": [{"state": "arisen", **_now(state)}]}
            new.append({"id": qid, "text": q["text"]})
    return new


def set_question_state(state: GameState, qid: str, new_state: str) -> dict:
    """QST-01/REC-04: 떠오른 질문만 붙잡거나(held), 다시 생각하거나(revisited), 내려놓을(arisen) 수 있다. 0턴."""
    q = state.questions.get(qid)
    if q is None:
        raise ValueError("아직 떠오르지 않은 질문이다.")
    if new_state not in ic.QUESTION_STATES:
        raise ValueError("알 수 없는 질문 상태다.")
    if q["state"] != new_state:
        q["state"] = new_state
        q["history"].append({"state": new_state, **_now(state)})
    return {"id": qid, "state": new_state, "label": ic.QUESTION_STATE_LABELS[new_state]}


# ------------------------------------------------------------------ 지식
def raise_knowledge(state: GameState, kid: str, to_status: str, source: str) -> bool:
    """행동으로 지식 단계를 올린다. 이미 그 단계 이상이면 그대로 (단계를 낮추지 않는다)."""
    entry = state.knowledge.get(kid)
    if entry is None:
        return False
    if connection.KNOWLEDGE_RANK[entry["status"]] >= connection.KNOWLEDGE_RANK[to_status]:
        return False
    entry["status"] = to_status
    entry.setdefault("sources", []).append({"id": source, **_now(state)})
    return True


def read_wave_book(state: GameState) -> dict | None:
    """책장에서 파도 책이 눈에 들어오면 읽는다 (지식 '접함'). 조건이 안 되면 None."""
    book = ic.WAVE_BOOK
    if WAVE_THREAD in state.books_read or book["requires_fact"] not in state.fact_flags:
        return None
    state.books_read.append(WAVE_THREAD)
    state.infos.append(InfoEntry(id=uuid.uuid4().hex[:10], thread=WAVE_THREAD, day=state.day,
                                 source="book", text=book["info_text"]))
    window.record_knowledge(state, book["knowledge_id"], "book:" + WAVE_THREAD)
    return {"text": book["text"], "info_gained": book["info_text"], "knowledge": book["knowledge_id"]}


# ------------------------------------------------------------------ 파도 주기 탐구
def _action(state: GameState, action_id: str) -> dict:
    rec = state.actions_done.get(action_id)
    if rec is None:
        rec = {"count": 0, "history": []}
        state.actions_done[action_id] = rec
    return rec


def wave_available(state: GameState) -> bool:
    """파도를 한 번이라도 눈여겨본 뒤에 세기/측정 행동이 보인다 (질문 등록은 필요 없다)."""
    return "sea_wave" in state.fact_flags or "wave_interval" in state.fact_flags


def wave_count(state: GameState) -> dict:
    """세기: 시계 없이 어림으로 센다. 경험 '측정'으로는 치지 않는다."""
    if state.period in ic.WAVE_DARK_PERIODS:
        return {"text": ic.WAVE_DARK_TEXT, "turn_cost": 0}
    reading = world.wave_reading(state.day, state.period, state.seed, "count")
    lo = max(2, math.floor(reading) - 2)
    hi = lo + 4
    rec = _action(state, "wave_count")
    rec["count"] += 1
    rec["history"].append({**_now(state), "low": lo, "high": hi})
    window.record_facts(state, ["wave_interval", "sea_wave", "subject:sea"])
    return {"text": ic.WAVE_COUNT_TEXT.format(lo=lo, hi=hi), "turn_cost": 1,
            "questions": raise_questions(state, "act:wave_count")}


def _fmt(sec: float) -> str:
    return f"{sec:g}"


def wave_measure(state: GameState) -> dict:
    """기준점 재측정: 스톱워치로 열 번을 재서 나눈다. 경험 '파도_주기_측정'이 된다."""
    if state.period in ic.WAVE_DARK_PERIODS:
        return {"text": ic.WAVE_DARK_TEXT, "turn_cost": 0}
    sec = world.wave_reading(state.day, state.period, state.seed, "measure")
    rec = _action(state, "wave_measure")
    prev = rec["history"][-1] if rec["history"] else None
    prev_other_day = next((h for h in reversed(rec["history"]) if h["day"] != state.day), None)
    text = ic.WAVE_MEASURE_TEXT.format(sec=_fmt(sec))
    if prev and prev["day"] == state.day:
        text += ic.WAVE_MEASURE_SAME_DAY.format(sec=_fmt(prev["seconds"]))
    elif prev_other_day:
        text += ic.WAVE_MEASURE_OTHER_DAY.format(day=prev_other_day["day"], sec=_fmt(prev_other_day["seconds"]))
    rec["count"] += 1
    rec["history"].append({**_now(state), "seconds": sec})
    window.record_facts(state, ["wave_interval", "sea_wave", "subject:sea"])
    applied = False
    # 지식을 가진 채 다른 날의 기록과 비교하면 '적용함' (접함/이해함 어느 쪽에서든 — 순서를 강제하지 않는다).
    if prev_other_day and "wave_period" in state.knowledge:
        applied = raise_knowledge(state, "wave_period", "applied", "act:wave_measure")
        if applied:
            text += ic.WAVE_MEASURE_APPLIED
    return {"text": text, "turn_cost": 1, "seconds": sec, "knowledge_applied": applied,
            "questions": raise_questions(state, "act:wave_measure")}


def _wave_basis(state: GameState) -> tuple[str, dict]:
    measures = state.actions_done.get("wave_measure", {}).get("history", [])
    per_day: dict[int, list[float]] = {}
    for h in measures:
        per_day.setdefault(h["day"], []).append(h["seconds"])
    means = {d: sum(v) / len(v) for d, v in per_day.items()}
    basis = {"measured_days": sorted(means), "rough_counts": state.actions_done.get("wave_count", {}).get("count", 0)}
    if not means:
        return "rough_only", basis
    if len(means) == 1:
        return "one_day", basis
    spread = max(means.values()) - min(means.values())
    basis["spread"] = round(spread, 2)
    return ("days_differ" if spread >= ic.WAVE_DIFFER_THRESHOLD else "days_similar"), basis


def can_interpret(state: GameState) -> bool:
    return bool(state.actions_done.get("wave_count") or state.actions_done.get("wave_measure"))


def wave_interpret(state: GameState, choice: int) -> dict:
    """해석/검증: 플레이어가 고른 해석을 기록과 대조해 상태를 남긴다. 가설로 저장되고, 틀려도 지우지 않는다."""
    if not can_interpret(state):
        raise ValueError("아직 파도를 세거나 재 본 기록이 없다.")
    if not (isinstance(choice, int) and 0 <= choice < len(ic.WAVE_INTERPRETATIONS)):
        raise ValueError("고를 수 있는 해석이 아니다.")
    situation, basis = _wave_basis(state)
    status, note = ic.WAVE_VERDICTS[situation][choice]
    claim = ic.WAVE_INTERPRETATIONS[choice]
    text = f"책상에서 파도 기록을 놓고 생각해 봤다. “{claim}” {note}"

    hyp = next((h for h in state.hypotheses if h.thread == WAVE_THREAD), None)
    rev = HypothesisRevision(day=state.day, period=state.period, status=status, text=claim, reason=note)
    if hyp is None:
        hyp = Hypothesis(id=uuid.uuid4().hex[:10], thread=WAVE_THREAD, created_day=state.day,
                         text=claim, status=status, revisions=[rev])
        state.hypotheses.append(hyp)
    else:
        hyp.revisions.append(rev)
        hyp.status = status
    rec = _action(state, "wave_interpret")
    rec["count"] += 1
    rec["history"].append({**_now(state), "choice": choice, "status": status, "basis": basis})

    understood = False
    if basis["measured_days"]:  # 실제로 잰 기록이 있어야 '주기'라는 개념과 연결된다
        understood = raise_knowledge(state, "wave_period", "understood", "act:wave_interpret")
        if understood:
            text += ic.WAVE_UNDERSTOOD_TEXT
    return {"text": text, "turn_cost": 1, "status": status, "knowledge_understood": understood}


def wave_view(state: GameState) -> dict:
    return {
        "available": wave_available(state),
        "can_interpret": can_interpret(state),
        "interpretations": list(ic.WAVE_INTERPRETATIONS),
        "measurements": [{"day": h["day"], "period": h["period"], "period_label": PERIOD_LABELS[h["period"]],
                          "seconds": h["seconds"]}
                         for h in state.actions_done.get("wave_measure", {}).get("history", [])],
        "counts": [{"day": h["day"], "period": h["period"], "period_label": PERIOD_LABELS[h["period"]],
                    "low": h["low"], "high": h["high"]}
                   for h in state.actions_done.get("wave_count", {}).get("history", [])],
    }


# ------------------------------------------------------------------ 기록 화면 (REC-03, 0턴)
def knowledge_label(kid: str) -> str:
    return wc.KNOWLEDGE_LABELS.get(kid) or ic.EXTRA_KNOWLEDGE_LABELS.get(kid) or kid


def _source_label(sid: str) -> str:
    if sid.startswith("book:"):
        return "책"
    if sid.startswith("act:"):
        return {"act:wave_measure": "파도 측정", "act:wave_interpret": "파도 기록 해석",
                "act:wave_count": "파도 세기"}.get(sid, "탐구")
    if sid in wc.OBSERVATIONS:
        return "창밖 관찰 " + sid
    return sid


def _connection_text(cid: str, stage: int, trigger: str) -> str:
    for d in connection.CONNECTIONS:
        if d["id"] == cid and d["stage"] == stage and trigger in d["trigger"]:
            return d["text"]
    return ""


def records_view(state: GameState) -> dict:
    def when(t: dict) -> str:
        return f"{t['day']}일차 {PERIOD_LABELS.get(t['period'], t['period'])}"

    observations = []
    for oid, h in state.observation_history.items():
        observations.append({"id": oid, "kind": "window", "text": window.render_text(state, wc.OBSERVATIONS[oid]["text"]),
                             "first": when(h["first_seen"]), "last": when(h["last_seen"]), "count": h["count"],
                             "_order": (h["first_seen"]["day"], h["first_seen"]["period"])})
    for o in state.observations:  # 기존 세계 이벤트 관찰(밤바다 불빛 등)
        observations.append({"id": o.id, "kind": "event", "text": o.text, "first": when({"day": o.day, "period": o.period}),
                             "last": None, "count": 1, "_order": (o.day, o.period)})
    for kind, label in (("wave_count", "파도 세기"), ("wave_measure", "파도 측정")):
        for h in state.actions_done.get(kind, {}).get("history", []):
            value = f"약 {_fmt(h['seconds'])}초" if kind == "wave_measure" else f"{h['low']}~{h['high']}초쯤"
            observations.append({"id": kind, "kind": "measure", "text": f"[{label}] {value}", "first": when(h),
                                 "last": None, "count": 1, "_order": (h["day"], h["period"])})
    observations.sort(key=lambda x: (x["_order"][0], PERIODS.index(x["_order"][1])))
    for o in observations:
        del o["_order"]

    questions = [{"id": qid, "text": ic.QUESTIONS[qid]["text"], "state": q["state"],
                  "state_label": ic.QUESTION_STATE_LABELS[q["state"]], "first": when(q["first"])}
                 for qid, q in state.questions.items() if qid in ic.QUESTIONS]
    knowledge = [{"id": kid, "label": knowledge_label(kid), "status": k["status"],
                  "status_label": ic.KNOWLEDGE_STATUS_LABELS.get(k["status"], k["status"]),
                  "sources": [_source_label(s["id"]) for s in k.get("sources", [])]}
                 for kid, k in state.knowledge.items()]
    connections = []
    for cid, rec in state.connections.items():
        for h in rec.get("history", []):
            connections.append({"id": cid, "stage": h["stage"], "day": h["day"],
                                "text": window.render_text(state, _connection_text(cid, h["stage"], h["trigger_observation_id"]))})
    hypotheses = [{"thread": h.thread, "text": h.latest_text(), "status": h.status,
                   "status_label": HYPOTHESIS_STATUS_LABELS.get(h.status, h.status)} for h in state.hypotheses]
    return {"observations": observations, "questions": questions, "knowledge": knowledge,
            "connections": connections, "hypotheses": hypotheses,
            "question_state_labels": ic.QUESTION_STATE_LABELS}
