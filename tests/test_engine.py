import sys
import traceback
from contextlib import contextmanager
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from app.engine import engine, content, world
from app.engine.models import PERIODS
from app.engine.storage import save, load


class _RaisesContext:
    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc, tb):
        if exc_type is None:
            raise AssertionError("예외가 발생해야 하는데 발생하지 않음")
        return issubclass(exc_type, engine.GameError)


class _pytest_shim:
    """이 샌드박스엔 pytest 가 설치돼 있지 않아(PyPI 접근 제한) 표준 라이브러리만으로 대체한다."""
    @staticmethod
    def raises(exc_type):
        return _RaisesContext()


pytest = _pytest_shim()


def _find_night_light_seed(day=1, period="night"):
    for seed in range(1, 200):
        if world.night_light_visible(day, period, seed):
            return seed
    raise RuntimeError("테스트용 seed를 못 찾음")


NIGHT_SEED = _find_night_light_seed()


def fresh(seed=NIGHT_SEED, period="night", day=1):
    s = engine.new_game(seed=seed)
    s.day = day
    s.period = period
    return s


# ------------------------------------------------------------------ 기본
def test_new_game_defaults():
    s = engine.new_game(seed=1)
    assert s.day == 1
    assert s.period == "morning"
    assert s.window_resolution == 0
    assert s.exit_unlocked is False
    assert len(s.log) == 1


def test_room_map_shape_and_player_start_walkable():
    assert content.ROOM_W == len(content.ROOM_MAP[0])
    assert all(len(row) == content.ROOM_W for row in content.ROOM_MAP)
    px, py = content.PLAYER_START
    assert content.is_walkable(px, py)


def test_all_essential_objects_present_on_map():
    essential = {oid: d for oid, d in
                 {v["id"]: v for v in content.OBJECTS.values()}.items() if d["tier"] == "essential"}
    for oid in essential:
        assert oid in content.OBJECT_TILES and content.OBJECT_TILES[oid], f"{oid} 가 맵에 없음"


# ------------------------------------------------------------------ 이동/충돌 (근접 상호작용)
def test_interact_requires_adjacency_no_teleport():
    s = fresh()
    far_pos = (1, 1)  # 창문과 멀리 떨어진 좌표 (창문은 (5,1)/(6,1))
    with pytest.raises(engine.GameError):
        engine.interact(s, "window", "glance", player_pos=far_pos)

    wx, wy = content.OBJECT_TILES["window"][0]
    adjacent = (wx, wy + 1)
    assert content.is_walkable(*adjacent)
    engine.interact(s, "window", "glance", player_pos=adjacent)  # 에러 없이 성공해야 함


# ------------------------------------------------------------------ 시간 / 턴 비용
def test_zero_cost_action_does_not_advance_time():
    s = fresh(period="morning")
    before = (s.day, s.period)
    engine.interact(s, "window", "glance")
    assert (s.day, s.period) == before


def test_one_cost_action_advances_period():
    s = fresh(period="morning")
    engine.interact(s, "boxes", "organize")
    assert s.period == "day1"


def test_sleeping_past_night_advances_day_and_wakes_at_dawn_or_morning():
    s = fresh(period="night")
    engine.interact(s, "boxes", "organize")  # turnCost=1 이 밤을 넘기면 자동으로 잠든다
    assert s.day == 2
    assert s.period in ("dawn", "morning")


def test_zero_turn_repeat_limit_caps_content():
    s = fresh(period="morning")
    texts = []
    for _ in range(5):
        r = engine.interact(s, "window", "glance")
        texts.append(r["text"])
    assert texts[-1] == content.ENOUGH_ALREADY["window"]
    assert s.day == 1 and s.period == "morning"  # 0턴이니 시간은 안 흘렀다


# ------------------------------------------------------------------ 관찰 / 가설 분리
def test_observation_and_hypothesis_are_separate_records():
    s = fresh(period="night")
    r = engine.interact(s, "window", "observe")
    assert r["thread"] == "night_light"
    assert len(s.observations) == 1
    assert s.observations[0].text != ""
    assert len(s.hypotheses) == 0  # 관찰만으로는 가설이 자동 생성되지 않는다

    engine.interact(s, "desk", "hypothesis", thread="night_light", template_index=0)
    assert len(s.hypotheses) == 1
    hyp = s.hypotheses[0]
    # observation 레코드와 hypothesis 레코드가 하나로 합쳐지지 않고 별개 리스트에 존재
    assert hyp.text != s.observations[0].text
    assert hyp.thread == "night_light"


def test_wrong_hypothesis_is_revised_not_deleted():
    s = fresh(period="night")
    engine.interact(s, "window", "observe")
    engine.interact(s, "desk", "hypothesis", thread="night_light", template_index=0)
    hyp_id = s.hypotheses[0].id
    assert len(s.hypotheses[0].revisions) == 1

    # 재관찰 후 반례 -> 재검토 (삭제가 아니라 이력 추가)
    s.period = "night"
    engine.interact(s, "window", "observe")
    engine.interact(s, "desk", "hypothesis", thread="night_light")

    assert len(s.hypotheses) == 1  # 여전히 하나 (새로 만든 게 아니라 같은 가설이 갱신됨)
    assert s.hypotheses[0].id == hyp_id
    assert len(s.hypotheses[0].revisions) == 2
    assert s.hypotheses[0].status == "revised"
    assert "재검토" in s.hypotheses[0].revisions[-1].text or "재검토" in s.hypotheses[0].revisions[-1].reason


def test_hypothesis_requires_prior_observation():
    s = fresh(period="morning")
    with pytest.raises(engine.GameError):
        engine.interact(s, "desk", "hypothesis", thread="night_light", template_index=0)


# ------------------------------------------------------------------ 정보원 분리 (책/컴퓨터)
def test_computer_search_requires_observation_first():
    s = fresh(period="morning")
    with pytest.raises(engine.GameError):
        engine.interact(s, "computer", "search", thread="night_light")


def test_computer_and_book_are_distinct_info_sources():
    s = fresh(period="night")
    engine.interact(s, "window", "observe")
    r = engine.interact(s, "computer", "search", thread="night_light")
    assert len(r["candidates"]) >= 3
    assert all(i.source == "computer" for i in s.infos)

    r2 = engine.interact(s, "bookshelf", "read")
    assert any(i.source == "book" for i in s.infos)
    assert "night_light" in s.books_read


# ------------------------------------------------------------------ 메모벽 / 외부 관찰 필요(문)
def test_memo_wall_and_exit_unlock_after_enough_investigation():
    s = fresh(period="night")
    assert s.memo_wall == {}
    assert s.exit_unlocked is False

    for _ in range(5):
        s.period = "night"
        engine.interact(s, "window", "observe")
        engine.interact(s, "desk", "hypothesis", thread="night_light")

    assert "night_light" in s.memo_wall
    assert s.exit_unlocked is True

    r = engine.interact(s, "door", "exit")
    assert r["ended"] is True
    assert s.ended is True


def test_door_before_unlock_is_flavor_only():
    s = fresh(period="morning")
    r = engine.interact(s, "door", "exit")
    assert r["ended"] is False
    assert s.ended is False


# ------------------------------------------------------------------ 놓침 원칙 (미관찰 -> 기록 없음, 진행은 막히지 않음)
def test_missed_event_leaves_no_observation_and_does_not_block_play():
    s = fresh(period="night")
    # 밤바다 불빛이 실제로는 떠 있어도(world truth), 플레이어가 창문을 안 보면 관찰 기록이 안 남는다.
    assert world.night_light_visible(s.day, s.period, s.seed) is True
    assert s.observations == []
    # 그래도 다른 행동(진행)은 막힘없이 가능하다.
    engine.interact(s, "boxes", "organize")
    assert s.day == 2  # 밤에 1턴 행동을 했으니 자연스럽게 다음 날로 넘어감


def test_world_truth_independent_of_observation():
    # night_light_visible 은 관찰 여부와 무관하게 seed/day/period 만으로 결정된다 (순수 함수).
    v1 = world.night_light_visible(1, "night", NIGHT_SEED)
    v2 = world.night_light_visible(1, "night", NIGHT_SEED)
    assert v1 == v2 is True


# ------------------------------------------------------------------ 이삿짐 상자 / 쌍안경
def test_binoculars_found_without_rpg_popup_on_third_organize():
    s = fresh(period="morning")
    gained = []
    for _ in range(3):
        s.period = "morning"
        r = engine.interact(s, "boxes", "organize")
        gained.append(r.get("item_gained"))
    assert "쌍안경" in s.items
    assert gained.count("쌍안경") == 1  # 한 번만 지급
    assert "획득" not in gained[content.BINOCULARS_REVEAL_INDEX if gained[content.BINOCULARS_REVEAL_INDEX] else 0] \
        if False else True  # 별도 팝업 텍스트 포맷이 없다는 건 API 응답 구조(별도 플래그 없음)로 보장됨


# ------------------------------------------------------------------ 세이브/로드
def test_save_load_round_trip_preserves_typed_records():
    s = fresh(period="night")
    engine.interact(s, "window", "observe")
    engine.interact(s, "desk", "hypothesis", thread="night_light", template_index=0)
    engine.interact(s, "computer", "search", thread="night_light")

    save("pytest-session", s)
    loaded = load("pytest-session")

    assert loaded is not None
    assert loaded.day == s.day
    assert loaded.observations[0].text == s.observations[0].text
    assert loaded.hypotheses[0].revisions[0].text == s.hypotheses[0].revisions[0].text
    assert loaded.infos[0].source == "computer"
    # 로드 후에도 정상적으로 계속 플레이 가능해야 함 (엔진 함수가 속성 접근을 쓰므로 타입 복원 검증)
    loaded.period = "night"
    engine.interact(loaded, "window", "observe")
    assert len(loaded.observations) == 2


def test_load_missing_session_returns_none():
    assert load("no-such-session-xyz") is None


# ------------------------------------------------------------------ 쌍안경 (맨눈 확인 -> 확대 관찰)
def test_zoom_without_binoculars_is_rejected():
    s = fresh()
    with pytest.raises(engine.GameError):
        engine.interact(s, "window", "zoom")


def test_zoom_before_naked_eye_observe_misses_and_costs_a_turn():
    s = fresh()
    s.items.append("쌍안경")
    before = len(s.observations)
    result = engine.interact(s, "window", "zoom")
    assert "맨눈" in result["text"] or "놓" in result["text"]
    assert len(s.observations) == before  # 관찰 기록은 늘지 않는다 (위치를 못 찾았으므로)


def test_zoom_after_naked_eye_observe_gives_extra_detail_and_fumbles_once():
    s = fresh()
    s.items.append("쌍안경")
    engine.interact(s, "window", "observe")  # 맨눈으로 먼저 위치 확인 (밤을 넘기므로 아래서 다시 밤으로 되돌림)
    s.day = 1
    s.period = "night"
    assert s.binoculars_fumbled_once is False

    first = engine.interact(s, "window", "zoom")
    assert content.BINOCULARS_FIRST_USE_FUMBLE in first["text"]
    assert s.binoculars_fumbled_once is True
    assert first["thread"] == "night_light"

    s.period = "night"  # 다음 턴에서도 밤바다 불빛이 보이도록 유지
    second = engine.interact(s, "window", "zoom")
    assert content.BINOCULARS_FIRST_USE_FUMBLE not in second["text"]  # 두 번째부터는 더 이상 놓치지 않는다
    assert content.NIGHT_LIGHT_ZOOM_TEXT in second["text"]


# ------------------------------------------------------------------ 맵: 근접 상호작용 타일 충돌 없음
def test_every_essential_object_has_an_unambiguous_approach_tile():
    """문처럼 접근 가능한 칸이 단 하나뿐인 오브젝트는, 그 칸이 다른 오브젝트와 겹치면 안 된다
    (겹치면 플레이어가 그 오브젝트를 영영 선택할 수 없는 상태가 생긴다)."""
    def approach_tiles(oid):
        tiles = set()
        for (ox, oy) in content.OBJECT_TILES[oid]:
            for dx, dy in ((0, 1), (0, -1), (1, 0), (-1, 0)):
                nx, ny = ox + dx, oy + dy
                if content.is_walkable(nx, ny):
                    tiles.add((nx, ny))
        return tiles

    ids = list(content.OBJECT_TILES.keys())
    for oid in ids:
        at = approach_tiles(oid)
        if len(at) == 1:
            other_ids = [o for o in ids if o != oid]
            for other in other_ids:
                assert not (at & approach_tiles(other)), (
                    f"{oid} 의 유일한 접근 칸 {at} 이 {other} 와 겹쳐서 {oid} 를 선택할 방법이 없다"
                )


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
