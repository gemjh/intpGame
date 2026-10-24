"""2차 범위 테스트: 준비된 질문 / 지식 단계 / 파도 주기 탐구 / 기록 화면 (요구사항명세서 261011 4·5·8장).
실행: python3 tests/test_inquiry.py"""
import sys
import traceback
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.engine import engine, inquiry, window, window_content as wc, world
from app.engine.models import PERIODS
from app.engine.storage import SAVE_DIR, save, load


def _seed():
    """앞마당 고양이/낮달/귀환이 끼어들지 않고, 1·2일차 파도 주기가 1초 이상 다른 seed."""
    for seed in range(1, 5000):
        if any(world.cat_in_yard(1, p, seed) for p in ("morning", "day1")):
            continue
        if abs(world.wave_period(1, seed) - world.wave_period(2, seed)) < 2.0:
            continue
        return seed
    raise RuntimeError("조건에 맞는 seed 없음")


SEED = _seed()


def fresh(period="morning", day=1):
    s = engine.new_game(seed=SEED)
    s.day, s.period = day, period
    return s


@contextmanager
def forced(oid):
    orig = window.pick
    window.pick = lambda state, subject, rng=None: (wc.OBSERVATIONS[oid]["subject"], wc.OBSERVATIONS[oid])
    try:
        yield
    finally:
        window.pick = orig


def see(s, oid, period="morning"):
    s.period = period
    with forced(oid):
        return engine.interact(s, "window", "observe", subject=wc.OBSERVATIONS[oid]["subject"])


def act(s, obj, action, period=None, **payload):
    if period:
        s.period = period
    return engine.interact(s, obj, action, **payload)


# ------------------------------------------------------------------ 질문 (QST-01, REC-04)
def test_question_arises_from_observation_and_can_be_held_or_skipped():
    s = fresh()
    r = see(s, "SEA-004")
    assert r["questions"] == [{"id": "wave_interval_constant", "text": "파도 간격은 정말 일정할까?"}]
    assert s.questions["wave_interval_constant"]["state"] == "arisen"   # 건너뛰면 떠오름 상태로 남는다
    r = see(s, "SEA-004")
    assert r["questions"] == []  # 같은 질문이 다시 떠오르지 않는다
    before = (s.day, s.period)
    r = act(s, "records", "question", question="wave_interval_constant", question_state="held")
    assert r["turn_cost"] == 0 and (s.day, s.period) == before  # REC-03: 기록 조작은 0턴
    assert s.questions["wave_interval_constant"]["state"] == "held"


def test_question_states_are_not_done_undone():
    s = fresh()
    see(s, "SEA-018")
    for st in ("held", "revisited", "arisen", "revisited"):
        act(s, "records", "question", question="tide_vs_wave", question_state=st)
        assert s.questions["tide_vs_wave"]["state"] == st
    for bad in ("done", "complete", "failed"):
        try:
            act(s, "records", "question", question="tide_vs_wave", question_state=bad)
        except engine.GameError:
            continue
        raise AssertionError(bad)
    assert [h["state"] for h in s.questions["tide_vs_wave"]["history"]] == \
        ["arisen", "held", "revisited", "arisen", "revisited"]


def test_cannot_register_question_that_never_arose():
    s = fresh()
    try:
        act(s, "records", "question", question="tide_vs_wave", question_state="held")
    except engine.GameError:
        pass
    else:
        raise AssertionError("떠오르지 않은 질문이 등록됨")
    assert s.questions == {}


# ------------------------------------------------------------------ 측정 (8장: 질문 없이 측정, 나중에 질문 등록)
def test_measure_at_sea_without_question_then_register_later():
    s = fresh()
    see(s, "SEA-001")  # 파도를 한 번 눈여겨봄 (sea_wave) — 질문은 아직 없다
    assert s.questions == {}
    r = act(s, "window", "wave_measure", period="day1")
    assert r["turn_cost"] == 1 and "초" in r["text"]
    assert s.actions_done["wave_measure"]["count"] == 1
    assert [q["id"] for q in r["questions"]] == ["wave_interval_constant"]  # 측정하다 떠오름
    act(s, "records", "question", question="wave_interval_constant", question_state="held")  # 나중에 등록
    assert s.questions["wave_interval_constant"]["state"] == "held"


def test_wave_actions_need_having_looked_at_waves():
    s = fresh()
    for a in ("wave_count", "wave_measure"):
        try:
            act(s, "window", a)
        except engine.GameError:
            continue
        raise AssertionError(a)
    assert s.actions_done == {}


def test_dark_periods_cannot_measure_and_cost_nothing():
    s = fresh()
    see(s, "SEA-001")
    for p in ("evening", "night"):
        r = act(s, "window", "wave_measure", period=p)
        assert r["turn_cost"] == 0 and s.period == p
    assert "wave_measure" not in s.actions_done


def test_count_is_not_measurement_experience():
    s = fresh()
    see(s, "SEA-004")
    act(s, "window", "wave_count", period="day1")
    s.knowledge["wave_period"] = {"status": "encountered", "sources": []}
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-013"])


def test_measurements_are_deterministic_world_values():
    s = fresh()
    see(s, "SEA-001")
    r = act(s, "window", "wave_measure", period="day1")
    assert abs(r["seconds"] - world.wave_period(1, SEED)) <= 0.5


# ------------------------------------------------------------------ 지식 (KNW-01/02)
def test_wave_book_needs_waves_seen_and_gives_encountered_once():
    s = fresh()
    act(s, "bookshelf", "read")
    assert "wave_period" not in s.knowledge  # 파도를 보기 전에는 눈에 안 들어온다
    see(s, "SEA-001")
    r = act(s, "bookshelf", "read", period="day1")
    assert r["new_knowledge"] == ["wave_period"]
    assert s.knowledge["wave_period"]["status"] == "encountered"
    for _ in range(3):
        act(s, "bookshelf", "read", period="day1")
    assert len(s.knowledge["wave_period"]["sources"]) == 1  # 중복 획득 없음


def test_knowledge_first_then_measure_unlocks_SEA013():
    s = fresh()
    see(s, "SEA-001")
    act(s, "bookshelf", "read", period="day1")         # 지식 선행 습득
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-013"])
    act(s, "window", "wave_measure", period="day1")
    assert window.requirements_met(s, wc.OBSERVATIONS["SEA-013"])


def test_measure_first_then_knowledge_unlocks_SEA013():
    s = fresh()
    see(s, "SEA-001")
    act(s, "window", "wave_measure", period="day1")
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-013"])
    act(s, "bookshelf", "read", period="day1")
    assert window.requirements_met(s, wc.OBSERVATIONS["SEA-013"])


def test_repeat_reading_or_viewing_does_not_raise_stage():
    s = fresh()
    see(s, "SEA-001")
    act(s, "bookshelf", "read", period="day1")
    act(s, "window", "wave_measure", period="day1")
    for _ in range(5):
        see(s, "SEA-013", period="day1")
        act(s, "bookshelf", "read", period="day1")
    assert s.knowledge["wave_period"]["status"] == "encountered"


def test_interpret_with_measurement_makes_understood():
    s = fresh()
    see(s, "SEA-001")
    act(s, "bookshelf", "read", period="day1")
    act(s, "window", "wave_measure", period="day1")
    r = act(s, "desk", "wave_interpret", choice=2)
    assert r["knowledge_understood"] is True
    assert s.knowledge["wave_period"]["status"] == "understood"


def test_interpret_with_only_rough_counts_does_not_understand():
    s = fresh()
    see(s, "SEA-001")
    act(s, "bookshelf", "read", period="day1")
    act(s, "window", "wave_count", period="day1")
    r = act(s, "desk", "wave_interpret", choice=2)
    assert r["status"] == "possible" and s.knowledge["wave_period"]["status"] == "encountered"


def test_measuring_on_another_day_with_knowledge_applies_without_forced_order():
    s = fresh()
    see(s, "SEA-001")
    act(s, "bookshelf", "read", period="day1")  # 접함 (해석은 건너뜀)
    act(s, "window", "wave_measure", period="day1")
    s.day, s.period = 2, "morning"
    r = act(s, "window", "wave_measure")
    assert "지난번(1일차)" in r["text"] and r["knowledge_applied"] is True
    assert s.knowledge["wave_period"]["status"] == "applied"   # 이해함을 거치지 않아도 된다
    act(s, "desk", "wave_interpret", choice=1)
    assert s.knowledge["wave_period"]["status"] == "applied"   # 단계가 내려가지 않는다


# ------------------------------------------------------------------ 해석/검증
def test_interpretation_follows_records_and_never_confirms():
    s = fresh()
    see(s, "SEA-001")
    try:
        act(s, "desk", "wave_interpret", choice=0)
    except engine.GameError:
        pass
    else:
        raise AssertionError("기록 없이 해석됨")
    act(s, "window", "wave_measure", period="day1")
    assert act(s, "desk", "wave_interpret", choice=0)["status"] == "guess"     # 하루치
    s.day, s.period = 2, "morning"
    act(s, "window", "wave_measure")
    r0 = act(s, "desk", "wave_interpret", choice=0)  # seed 상 1·2일차가 2초 이상 다르다
    r1 = act(s, "desk", "wave_interpret", choice=1)
    assert r0["status"] == "guess" and r1["status"] == "likely"
    hyp = next(h for h in s.hypotheses if h.thread == "wave_period")
    assert [r.status for r in hyp.revisions] == ["guess", "guess", "likely"]  # 틀린 해석도 지우지 않는다
    assert all(r.status != "near_certain" for r in hyp.revisions)


def test_wave_exploration_does_not_touch_memo_wall_or_exit():
    s = fresh()
    see(s, "SEA-001")
    for d in range(1, 6):
        s.day = d
        act(s, "window", "wave_measure", period="day1")
        act(s, "desk", "wave_interpret", choice=1)
    assert s.memo_wall == {} and s.exit_unlocked is False and "wave_period" not in s.threads


# ------------------------------------------------------------------ 중단·재개·저장 (8장)
def test_interrupt_resume_and_save_keep_state():
    sid = "test-inquiry-resume"
    try:
        s = fresh()
        see(s, "SEA-004")
        act(s, "records", "question", question="wave_interval_constant", question_state="held")
        act(s, "window", "wave_count", period="day1")
        save(sid, s)
        loaded = load(sid)
        assert loaded.questions == s.questions and loaded.actions_done == s.actions_done
        loaded.day, loaded.period = 4, "morning"  # 며칠 뒤 재개
        act(loaded, "bookshelf", "read")
        act(loaded, "window", "wave_measure", period="day1")
        act(loaded, "desk", "wave_interpret", choice=2)
        save(sid, loaded)
        again = load(sid)
        assert again.knowledge["wave_period"]["status"] == "understood"
        assert again.questions["wave_interval_constant"]["state"] == "held"
        assert [h.thread for h in again.hypotheses] == ["wave_period"]
        assert again.actions_done["wave_count"]["count"] == 1 and again.actions_done["wave_measure"]["count"] == 1
    finally:
        (SAVE_DIR / f"{sid}.json").unlink(missing_ok=True)


def test_old_save_without_phase2_data_loads():
    s = fresh()
    see(s, "SEA-001")
    from app.engine.models import state_from_dict, state_to_dict
    d = state_to_dict(s)
    for k in ("questions", "actions_done"):
        d.pop(k)
    loaded = state_from_dict(d)
    assert loaded.questions == {} and loaded.actions_done == {}
    assert act(loaded, "window", "wave_measure", period="day1")["turn_cost"] == 1


def test_question_condition_without_content_stays_locked():
    s = fresh()
    s.fact_flags["wave_direction"] = {"first": {"day": 1, "period": "morning"}, "days": [1], "seen_at": []}
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-035"])  # 파도 방향 질문 콘텐츠 없음


# ------------------------------------------------------------------ 기록 화면 (REC-03)
def test_records_view_has_four_tabs_and_is_free():
    s = fresh()
    see(s, "YARD-002")
    see(s, "DOG-004")  # C02 연결
    see(s, "SEA-004")
    act(s, "window", "wave_measure", period="day1")
    snap = engine.snapshot(s)
    rec = snap["records"]
    assert {"observations", "questions", "knowledge", "connections"} <= set(rec)
    assert any(o["kind"] == "measure" for o in rec["observations"])
    assert rec["questions"][0]["state_label"] == "떠오름"
    assert {k["label"] for k in rec["knowledge"]} >= {"개미 페로몬 의사소통"}
    assert rec["connections"][0]["text"].startswith("개미도 냄새로")
    assert snap["wave"]["available"] and snap["wave"]["can_interpret"]


if __name__ == "__main__":
    tests = [(name, obj) for name, obj in list(globals().items())
             if name.startswith("test_") and callable(obj)]
    passed, failed = 0, []
    for name, fn in tests:
        try:
            fn()
            passed += 1
            print(f"ok   {name}")
        except Exception:
            failed.append(name)
            print(f"FAIL {name}")
            traceback.print_exc()
    print(f"\n{passed}/{len(tests)} passed")
    if failed:
        print("failed:", ", ".join(failed))
        sys.exit(1)
