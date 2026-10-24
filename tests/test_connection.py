"""자동 연결 시스템 테스트 (자동연결시스템_개발명세서_261012 7장 T01~T10). 실행: python3 tests/test_connection.py"""
import json
import sys
import traceback
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.engine import connection, engine, window, window_content as wc, world
from app.engine.models import PERIODS, state_from_dict, state_to_dict
from app.engine.storage import SAVE_DIR, save, load


def _seed(night_light_day1: bool):
    """앞마당 고양이/낮달/아저씨 귀환이 끼어들지 않는 seed. C04 용은 1일차 밤에 밤바다 불빛이 보이는 seed."""
    for seed in range(1, 5000):
        if any(world.cat_in_yard(d, p, seed) for d in (1, 2) for p in ("morning", "day1", "day2")):
            continue
        if night_light_day1 != world.night_light_visible(1, "night", seed):
            continue
        return seed
    raise RuntimeError("조건에 맞는 seed 없음")


QUIET, NIGHT = _seed(False), _seed(True)


def fresh(period="day1", day=1, seed=QUIET):
    s = engine.new_game(seed=seed)
    s.day, s.period = day, period
    return s


@contextmanager
def forced(oid):
    """일반 관찰 선택(가중치 추첨)을 우회해 정해진 문장이 나오게 한다. 나머지 경로는 실제 엔진 그대로."""
    orig = window.pick
    window.pick = lambda state, subject, rng=None: (wc.OBSERVATIONS[oid]["subject"], wc.OBSERVATIONS[oid])
    try:
        yield
    finally:
        window.pick = orig


def observe(s, oid, period="day1"):
    s.period = period
    with forced(oid):
        return engine.interact(s, "window", "observe", subject=wc.OBSERVATIONS[oid]["subject"])


# ------------------------------------------------------------------ T01 / T02 / T03
def test_T01_trigger_without_prior_experience_is_plain_observation():
    for trig in ("SEA-007", "SEA-009", "DOG-004", "DOG-023", "SEA-031"):
        s = fresh()
        r = observe(s, trig)
        assert r["connection"] is None, trig
        assert r["text"] == wc.OBSERVATIONS[trig]["text"]
        assert s.connections == {}


def test_T02_C01_wind_wave_connects_once_and_records_evidence():
    s = fresh()
    observe(s, "SEA-009")
    r = observe(s, "SEA-007")
    assert r["connection"] == {"id": "sea_wind_wave_contrast_v1", "stage": 1}
    assert r["text"].startswith(wc.OBSERVATIONS["SEA-007"]["text"] + " 전에는 바람이 거의 없는데도")
    assert "너울" not in r["text"]
    rec = s.connections["sea_wind_wave_contrast_v1"]
    assert rec["stage"] == 1 and rec["first_day"] == rec["last_day"] == 1
    assert rec["trigger_observation_id"] == "SEA-007" and rec["evidence"] == ["SEA-009", "SEA-007"]
    assert r["text"] in s.log[-1]  # 관찰 + 연결이 하나의 일지 항목


def test_T02_C01_reverse_order_uses_same_connection():
    s = fresh()
    observe(s, "SEA-007")
    r = observe(s, "SEA-009")
    assert r["connection"] == {"id": "sea_wind_wave_contrast_v1", "stage": 1}
    assert s.connections["sea_wind_wave_contrast_v1"]["evidence"] == ["SEA-007", "SEA-009"]


def test_T02_C02_ant_dog_after_pheromone_knowledge():
    for trig in ("DOG-004", "DOG-014", "DOG-018"):
        s = fresh()
        observe(s, "YARD-002")
        assert s.knowledge["ant_pheromone"]["status"] == "encountered"
        r = observe(s, trig)
        assert r["connection"] == {"id": "ant_dog_scent_v1", "stage": 1}, trig
        assert s.connections["ant_dog_scent_v1"]["evidence"] == ["YARD-002", trig]


def test_T02_C03_girl_dog_after_seeing_them_together():
    for prior in ("SHOP-012", "SHOP-027", "SHOP-032", "DOG-026"):
        s = fresh()
        observe(s, prior)
        r = observe(s, "DOG-023")
        assert r["connection"] == {"id": "girl_dog_familiar_v1", "stage": 1}, prior
        assert wc.DOG_NAME not in r["text"]  # 아직 모르는 이름을 단정하지 않는다


def test_T02_C04_night_light_buoy_in_daytime():
    s = fresh(period="night", seed=NIGHT)
    r = engine.interact(s, "window", "observe", subject="sea")
    assert r["thread"] == "night_light"  # 실제 기존 세계 이벤트로 밤바다 불빛을 봤다
    light_id = r["observation_id"]
    s.day += 1
    observe(s, "SEA-032", period="morning")  # 항로표지 지식을 접함 (이 관찰도 트리거)
    rec = s.connections["night_light_buoy_v1"]
    assert rec["evidence"] == [light_id, "SEA-032"]
    assert "같은 물체인지는 모르겠지만" in s.log[-1]


def test_T03_same_trigger_again_does_not_repeat():
    s = fresh()
    observe(s, "SEA-009")
    observe(s, "SEA-007")
    for oid in ("SEA-007", "SEA-009", "SEA-007"):
        r = observe(s, oid)
        assert r["connection"] is None
        assert r["text"] == wc.OBSERVATIONS[oid]["text"]
    assert len(s.connections["sea_wind_wave_contrast_v1"]["history"]) == 1


# ------------------------------------------------------------------ T04 / T05
def test_T04_save_reload_keeps_connections_without_duplicates():
    sid = "test-connection-T04"
    try:
        s = fresh()
        observe(s, "YARD-002")
        observe(s, "DOG-004")
        save(sid, s)
        loaded = load(sid)
        assert loaded.connections == s.connections
        r = observe(loaded, "DOG-018")
        assert r["connection"] is None
    finally:
        (SAVE_DIR / f"{sid}.json").unlink(missing_ok=True)


def test_T05_existing_v2_day17_save_loads_without_loss():
    real = sorted(SAVE_DIR.glob("*.json"))
    base = json.loads(real[0].read_text(encoding="utf-8")) if real else state_to_dict(fresh())
    base.pop("connections", None)
    base.update(version=2, day=17)
    loaded = state_from_dict(base)
    assert loaded.connections == {}
    for k in ("observation_history", "fact_flags", "knowledge", "window_recent", "log"):
        assert getattr(loaded, k) == base[k], k
    assert len(loaded.observations) == len(base["observations"])
    r = engine.interact(loaded, "window", "observe", subject="sea")
    assert r["turn_cost"] == 1 and loaded.day >= 17


# ------------------------------------------------------------------ T06 ~ T10
def test_T06_no_knowledge_talk_before_encountered():
    s = fresh(period="night", seed=NIGHT)
    engine.interact(s, "window", "observe", subject="sea")
    s.day += 1
    r = observe(s, "SEA-031", period="morning")  # 불빛은 봤지만 항로표지 정보는 아직 못 접함
    assert r["connection"] is None
    s = fresh()
    r = observe(s, "DOG-018")  # 개미 정보 없음
    assert r["connection"] is None and "개미" not in r["text"]


def test_T06_encountered_is_not_understood():
    s = fresh()
    observe(s, "YARD-002")
    d = {**next(c for c in connection.CONNECTIONS if c["case"] == "C02"), "id": "fixture_needs_understood",
         "prerequisites": [{"knowledge": "ant_pheromone", "min": "understood"}]}
    s.window_recent.clear()
    window.record_observation(s, wc.OBSERVATIONS["DOG-004"])
    assert connection.check(s, wc.OBSERVATIONS["DOG-004"], [d]) is None
    assert s.knowledge["ant_pheromone"]["status"] == "encountered"


def test_T06_C04_not_at_night():
    s = fresh(period="night", seed=NIGHT)
    engine.interact(s, "window", "observe", subject="sea")  # 관찰 후 시간이 다음 날로 넘어간다
    s.period = "night"
    window.record_observation(s, wc.OBSERVATIONS["SEA-032"])
    assert connection.check(s, wc.OBSERVATIONS["SEA-032"]) is None  # 낮에 본 부표만 트리거


def test_T07_connection_costs_no_extra_turn():
    a, b = fresh(), fresh()
    observe(b, "SEA-009", period="morning")
    ra = observe(a, "SEA-007", period="morning")
    rb = observe(b, "SEA-007", period="morning")
    assert ra["connection"] is None and rb["connection"] is not None
    assert ra["turn_cost"] == rb["turn_cost"] == 1
    assert (a.day, a.period) == (b.day, b.period) == (1, "day1")


def test_T08_connection_does_not_confirm_hypotheses_or_threads():
    s = fresh(period="night", seed=NIGHT)
    engine.interact(s, "window", "observe", subject="sea")
    s.day += 1
    observe(s, "SEA-032", period="morning")
    observe(s, "YARD-002")
    before = (repr(s.hypotheses), repr(s.threads), dict(s.memo_wall), s.exit_unlocked,
              json.dumps(s.knowledge, sort_keys=True), dict(s.questions))
    r = observe(s, "SEA-031", period="day1")
    assert r["connection"] is None  # C04 는 SEA-032 에서 이미 발생 — 반복 안 함
    r = observe(s, "DOG-004")
    assert r["connection"]["id"] == "ant_dog_scent_v1"
    after = (repr(s.hypotheses), repr(s.threads), dict(s.memo_wall), s.exit_unlocked,
             json.dumps(s.knowledge, sort_keys=True), dict(s.questions))
    assert before == after


def test_T09_new_evidence_outputs_next_stage_only():
    s = fresh()
    r = observe(s, "SEA-008")
    assert r["connection"] is None  # 1단계 전에는 2단계가 나오지 않는다
    observe(s, "SEA-009")
    observe(s, "SEA-007")
    r = observe(s, "SEA-008")
    assert r["connection"] == {"id": "sea_wind_wave_contrast_v1", "stage": 2}
    assert "짐작" in r["text"] and "전에는 바람이 거의 없는데도" not in r["text"]
    rec = s.connections["sea_wind_wave_contrast_v1"]
    assert rec["stage"] == 2 and [h["stage"] for h in rec["history"]] == [1, 2]
    for oid in ("SEA-008", "SEA-007", "SEA-009"):
        assert observe(s, oid)["connection"] is None


def test_T10_missing_content_ids_fail_validation():
    bad = [{"id": "fake", "stage": 1, "trigger": ["SEA-999"], "prerequisites": [], "text": "x"}]
    try:
        connection.validate(bad)
    except AssertionError:
        pass
    else:
        raise AssertionError("없는 관찰 ID 가 검증을 통과함")
    bad = [{"id": "fake", "stage": 1, "trigger": ["SEA-001"],
            "prerequisites": [{"knowledge": "swell", "min": "encountered"}], "text": "x"}]
    connection.validate(bad)  # 적용형 지식 ID 는 존재하지만 공급원이 없다 -> 런타임에 조용히 건너뜀
    s = fresh()
    window.record_observation(s, wc.OBSERVATIONS["SEA-001"])
    assert connection.check(s, wc.OBSERVATIONS["SEA-001"], bad) is None
    assert s.connections == {} and "swell" not in s.knowledge


def test_definitions_reference_real_content():
    connection.validate(connection.CONNECTIONS)
    assert {d["case"] for d in connection.CONNECTIONS} == {"C01", "C02", "C03", "C04"}


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
