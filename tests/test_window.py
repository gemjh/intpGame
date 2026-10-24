"""창문 관찰 시스템 테스트 (명세서 261011 8장 인수 기준). 실행: python3 tests/test_window.py"""
import random
import sys
import traceback
from collections import Counter
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.engine import engine, window, window_content as wc, world
from app.engine.models import PERIODS, state_from_dict, state_to_dict
from app.engine.storage import save, load


def _quiet_seed(day=1):
    """그날 밤바다 불빛/낮달/귀환/고양이 같은 기존 세계 이벤트가 끼어들지 않는 seed (문장 풀만 검증하려고)."""
    for seed in range(1, 5000):
        if world.weather_of(day, seed) != "비":
            continue  # 비 오는 날은 밤바다 불빛이 안 보인다
        if world.owner_returns_at_day(day, seed) or world.daytime_moon_visible(day, "day1", seed) \
                or world.daytime_moon_visible(day, "day2", seed):
            continue
        if any(world.cat_in_yard(day, p, seed) for p in PERIODS):
            continue
        return seed
    raise RuntimeError("조용한 seed를 못 찾음")


QUIET = _quiet_seed()


def fresh(period="day1", day=1, seed=QUIET):
    s = engine.new_game(seed=seed)
    s.day, s.period = day, period
    return s


# ------------------------------------------------------------------ 데이터
def test_148_sentences_with_spec_counts():
    c = Counter(o["subject"] for o in wc.OBSERVATIONS.values())
    assert len(wc.OBSERVATIONS) == 148
    assert c == {"sea": 38, "sky": 34, "shop": 35, "dog": 31, "yard": 10}


def test_data_contract_fields_present():
    keys = {"id", "subject", "text", "requirements", "knowledgeMode", "knowledgeIds",
            "repeatPolicy", "effects", "followUpIds"}
    for o in wc.OBSERVATIONS.values():
        assert keys <= set(o), o["id"]
        for f in o["followUpIds"]:
            assert f in wc.OBSERVATIONS


def test_confirmed_text_revisions_applied():
    assert wc.OBSERVATIONS["SEA-038"]["text"].startswith("바다를 한참 바라봤다. 그러다 배가 고파졌다.")
    assert "물 떨어" not in wc.OBSERVATIONS["SHOP-018"]["text"]
    assert "체취" in wc.OBSERVATIONS["DOG-023"]["text"]


def test_weather_scenes_have_no_conditions():
    for oid in ("DOG-009", "DOG-010", "DOG-014", "YARD-007", "SHOP-027", "SHOP-030"):
        assert wc.OBSERVATIONS[oid]["requirements"] == [], oid


# ------------------------------------------------------------------ 턴 / UX
def test_each_subject_and_free_look_costs_exactly_one_turn():
    for subject in [*wc.SUBJECTS, "any"]:
        s = fresh(period="morning")
        r = engine.interact(s, "window", "observe", subject=subject)
        assert r["turn_cost"] == 1
        assert (s.day, s.period) == (1, "day1"), subject
        if subject != "any":
            assert r["subject"] == subject
        assert r["text"]


def test_observe_subject_returns_sentence_of_that_subject():
    s = fresh()
    for _ in range(20):
        s.period = "day1"
        r = engine.interact(s, "window", "observe", subject="dog")
        assert r["observation_key"].startswith("DOG-")


def test_free_look_picks_an_available_subject_and_eligible_sentence():
    s = fresh()
    for _ in range(40):
        s.period = "day1"
        r = engine.interact(s, "window", "observe", subject="any")
        o = wc.OBSERVATIONS[r["observation_key"]]
        assert o["subject"] == r["subject"]


def test_no_cap_on_observations_per_period():
    s = fresh()
    for _ in range(10):
        s.period = "day1"
        r = engine.interact(s, "window", "observe", subject="sea")
        assert r["observation_key"] is not None  # 같은 시간대에 반복해도 막히지 않는다


def test_unknown_subject_rejected():
    s = fresh()
    try:
        engine.interact(s, "window", "observe", subject="moon")
    except engine.GameError:
        return
    raise AssertionError("알 수 없는 대상인데 에러가 안 남")


def test_legacy_region_restaurant_maps_to_shop():
    s = fresh()
    r = engine.interact(s, "window", "observe", region="restaurant")
    assert r["subject"] == "shop"


# ------------------------------------------------------------------ 조건 필터
def test_time_conditions_respected():
    s = fresh(period="night")
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-002"])  # 시간:낮
    assert window.requirements_met(s, wc.OBSERVATIONS["SKY-025"])      # 시간:밤
    s.period = "sunset"
    assert window.requirements_met(s, wc.OBSERVATIONS["SKY-015"])
    s.period = "evening"
    assert window.requirements_met(s, wc.OBSERVATIONS["DOG-012"])      # 시간:저녁/밤


def test_knowledge_apply_sentence_not_shown_before_knowledge():
    s = fresh()
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-033"])
    window.record_observation(s, wc.OBSERVATIONS["SEA-032"])  # 발견형: 항로표지 접함
    assert "navigation_marks" in s.knowledge
    assert window.requirements_met(s, wc.OBSERVATIONS["SEA-033"])


def test_measurement_and_question_conditions_stay_locked():
    s = fresh()
    s.knowledge["wave_period"] = {"status": "encountered", "sources": []}
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-013"])  # 경험:측정 없음
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-035"])  # 질문 없음
    assert not window.requirements_met(s, wc.OBSERVATIONS["DOG-029"])  # 선행 근거 없음(보류)


def test_record_conditions_today_vs_previous_day():
    s = fresh(day=3, period="morning")
    window.record_observation(s, wc.OBSERVATIONS["SEA-015"])        # 바위 드러남 (3일차 아침)
    assert window.requirements_met(s, wc.OBSERVATIONS["SEA-016"])   # "아까 보이던 바위"
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-017"])  # 같은 날이라 비교 불가
    s.day, s.period = 4, "day1"
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-016"])  # 다음 날엔 '아까'가 아님
    assert not window.requirements_met(s, wc.OBSERVATIONS["SEA-017"])  # 다른 날이지만 다른 시간대
    s.period = "morning"
    assert window.requirements_met(s, wc.OBSERVATIONS["SEA-017"])


def test_long_term_and_multi_record_conditions():
    s = fresh()
    for oid in ("YARD-002", "YARD-006"):
        window.record_observation(s, wc.OBSERVATIONS[oid])
    assert not window.requirements_met(s, wc.OBSERVATIONS["YARD-010"])
    window.record_observation(s, wc.OBSERVATIONS["YARD-008"])
    assert window.requirements_met(s, wc.OBSERVATIONS["YARD-010"])

    for d in (1, 2):
        s.day = d
        window.record_observation(s, wc.OBSERVATIONS["DOG-001"])
    assert not window.requirements_met(s, wc.OBSERVATIONS["DOG-031"])
    s.day = 3
    window.record_observation(s, wc.OBSERVATIONS["DOG-002"])
    assert window.requirements_met(s, wc.OBSERVATIONS["DOG-031"])


def test_conditioned_sentences_never_leak_in_long_random_play():
    """긴 무작위 플레이 동안 출력된 모든 문장은 출력 시점에 조건을 만족했어야 한다."""
    random.seed(7)
    s = engine.new_game(seed=12345)
    for _ in range(400):
        subject = random.choice([*wc.SUBJECTS, "any"])
        before = state_from_dict(state_to_dict(s))
        r = engine.interact(s, "window", "observe", subject=subject)
        key = r.get("observation_key")
        if key:
            o = wc.OBSERVATIONS[key]
            assert window.requirements_met(before, o), key
            assert window.repeat_allowed(before, o), key
    for locked in ("SEA-013", "SEA-035", "DOG-029", "SEA-012", "SKY-031"):
        assert locked not in s.observation_history, locked


# ------------------------------------------------------------------ 반복 / 폴백
def test_interval_repeat_not_twice_same_day():
    s = fresh()
    window.record_observation(s, wc.OBSERVATIONS["YARD-004"])
    assert not window.repeat_allowed(s, wc.OBSERVATIONS["YARD-004"])
    s.day += 1
    assert window.repeat_allowed(s, wc.OBSERVATIONS["YARD-004"])


def test_previous_sentence_excluded_when_enough_alternatives():
    s = fresh()
    window.record_observation(s, wc.OBSERVATIONS["SEA-001"])
    assert all(o["id"] != "SEA-001" for o in window.candidates(s, "sea"))


def test_recent_sentences_are_down_weighted():
    s = fresh()
    o = wc.OBSERVATIONS["SEA-003"]
    base = window._weight(s, o)
    window.record_observation(s, o)
    assert abs(window._weight(s, o) - base * wc.SEEN_MULT * wc.RECENT_MULT) < 1e-9


def test_exhausted_subject_falls_back_to_neutral_without_discovery():
    s = fresh()
    for oid, o in wc.OBSERVATIONS.items():
        if o["subject"] == "yard" and o["repeatPolicy"] == "interval":
            window.record_observation(s, o)  # 앞마당 간격 반복 문장을 오늘 다 봤다
    knowledge_before = dict(s.knowledge)
    hist_before = dict(s.observation_history)
    r = engine.interact(s, "window", "observe", subject="yard")
    assert r["turn_cost"] == 1
    assert r["observation_key"] is None
    assert r["text"] == wc.NEUTRAL_LINES["yard"]
    assert s.knowledge == knowledge_before and s.observation_history.keys() == hist_before.keys()


# ------------------------------------------------------------------ 기록 / 지식
def test_history_first_last_count_and_no_duplicate_first_discovery():
    s = fresh()
    o = wc.OBSERVATIONS["SKY-024"]
    r1 = window.record_observation(s, o)
    s.day = 2
    r2 = window.record_observation(s, o)
    h = s.observation_history["SKY-024"]
    assert r1["first_seen"] and not r2["first_seen"]
    assert h["count"] == 2 and h["first_seen"]["day"] == 1 and h["last_seen"]["day"] == 2
    assert r1["new_knowledge"] == ["moon_phase"] and r2["new_knowledge"] == []


def test_knowledge_not_duplicated_and_status_not_auto_raised():
    s = fresh()
    for oid in ("DOG-018", "DOG-018", "DOG-027"):
        window.record_observation(s, wc.OBSERVATIONS[oid])
    assert s.knowledge["dog_scent_marking"]["status"] == "encountered"
    assert len(s.knowledge["dog_scent_marking"]["sources"]) == 1
    assert window.requirements_met(s, wc.OBSERVATIONS["DOG-015"])  # 개의_후각 (any)


def test_output_count_alone_does_not_create_measurement_or_npc_history():
    s = fresh()
    for _ in range(30):
        window.record_observation(s, wc.OBSERVATIONS["SEA-004"])
    assert s.actions_done == {} and s.npc_relations == {} and s.questions == {}


def test_dog_name_substitution_after_name_known():
    s = fresh()
    assert window.subject_label(s, "dog") == "강아지"
    s.npc_relations["dog"] = {"name_known": True}
    assert window.subject_label(s, "dog") == "두식이"
    assert window.render_text(s, wc.OBSERVATIONS["SHOP-012"]["text"]).startswith("소녀가 두식이 집 앞에")


# ------------------------------------------------------------------ 저장 호환
def test_save_load_keeps_window_records():
    s = fresh()
    for _ in range(5):
        s.period = "day1"
        engine.interact(s, "window", "observe", subject="any")
    save("pytest-window", s)
    loaded = load("pytest-window")
    assert loaded.observation_history == s.observation_history
    assert loaded.fact_flags == s.fact_flags
    assert loaded.knowledge == s.knowledge
    engine.interact(loaded, "window", "observe", subject="sea")


def test_v1_save_without_new_fields_loads_and_plays():
    s = fresh()
    d = state_to_dict(s)
    for k in ("observation_history", "fact_flags", "knowledge", "window_recent", "questions",
              "actions_done", "npc_relations", "free_notes"):
        d.pop(k)
    d["version"] = 1
    loaded = state_from_dict(d)
    assert loaded.version == 2 and loaded.observation_history == {}
    r = engine.interact(loaded, "window", "observe", subject="sky")
    assert r["turn_cost"] == 1


def test_snapshot_exposes_window_subjects():
    snap = engine.snapshot(fresh())
    assert [x["id"] for x in snap["window"]["subjects"]] == wc.SUBJECTS
    assert snap["window"]["total"] == 148


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
