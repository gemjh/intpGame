"""
게임 엔진. 명세서 3~5장의 규칙을 구현한다.

핵심 규칙:
- 행동은 turnCost 0(잠깐 보기류) 또는 1(진짜 조사)이다. 0턴 행동은 한 시간대에 제한 횟수 이후
  "아까 충분히 봤다"류로 수렴해서 콘텐츠를 무한정 소모하지 못하게 한다.
- 세계는 (day, period, seed) 순수 함수로 결정되는 '사실'과, 플레이어가 실제로 관찰해서 생긴
  Observation 을 분리한다. 플레이어가 안 본 사건은 관찰 기록에 들어가지 않는다.
- 관찰(Observation) / 정보(InfoEntry) / 가설(Hypothesis) 은 끝까지 분리 저장한다.
"""
from __future__ import annotations

import random
import uuid
from dataclasses import asdict

from . import connection, content, inquiry, window, window_content, world
from .models import (
    GameState, Observation, InfoEntry, Hypothesis, HypothesisRevision,
    Discovery, ThreadState, HYPOTHESIS_STATUS_LABELS, PERIODS, PERIOD_LABELS, ZERO_TURN_REPEAT_LIMIT,
)


class GameError(Exception):
    def __init__(self, message: str, code: int = 400):
        super().__init__(message)
        self.message = message
        self.code = code


def new_game(seed: int | None = None) -> GameState:
    s = GameState(seed=seed if seed is not None else random.randint(1, 1_000_000))
    _log(s, "낯선 방. 창밖으로 바다가 보인다. 아직 다 풀지 못한 이삿짐 상자들이 쌓여 있다.")
    return s


def _log(state: GameState, text: str):
    label = PERIOD_LABELS.get(state.period, state.period)
    state.log.append(f"[D{state.day} {label}] {text}")
    if len(state.log) > 300:
        state.log = state.log[-300:]


def _new_id() -> str:
    return uuid.uuid4().hex[:10]


def _thread(state: GameState, thread_id: str) -> ThreadState:
    t = state.threads.get(thread_id)
    if t is None:
        t = ThreadState(id=thread_id)
        state.threads[thread_id] = t
    return t


def _check_zero_turn(state: GameState, object_id: str) -> bool:
    """True 면 이번엔 '아까 충분히 봤다'류로 보여줘야 함 (제한 초과)."""
    key = f"{object_id}|{state.day}|{state.period}"
    n = state.zero_turn_counts.get(key, 0)
    state.zero_turn_counts[key] = n + 1
    return n >= ZERO_TURN_REPEAT_LIMIT


def _advance_time(state: GameState, cost: int):
    if cost <= 0:
        return
    idx = PERIODS.index(state.period)
    idx += cost
    if idx < len(PERIODS):
        state.period = PERIODS[idx]
        return
    # 밤을 넘기면 자동으로 잠든다 - 다음 날 새벽 또는 아침에 깨어난다.
    state.day += 1
    state.period = random.choice(["dawn", "morning"])
    _log(state, f"── 어느새 잠들었다. Day {state.day} {PERIOD_LABELS[state.period]}, 눈을 떴다. ──")


def _record_interaction(state: GameState, object_id: str, action_id: str):
    state.interaction_log.append({
        "object": object_id, "action": action_id, "day": state.day, "period": state.period,
    })


def _add_observation(state: GameState, thread_id: str, text: str, tags: dict | None = None) -> Observation:
    obs = Observation(
        id=_new_id(), thread=thread_id, day=state.day, period=state.period,
        text=text, tags=tags or {},
    )
    state.observations.append(obs)
    t = _thread(state, thread_id)
    t.observation_count += 1
    _update_memo_wall(state, thread_id)
    _update_exit_ready(state, thread_id)
    return obs


def _update_memo_wall(state: GameState, thread_id: str):
    t = _thread(state, thread_id)
    stages = content.MEMO_WALL_QUESTION_STAGES.get(thread_id)
    if not stages:
        return
    thresh = content.MEMO_WALL_THRESHOLD
    if t.observation_count >= thresh["observations"] and t.revision_count >= thresh["revisions"]:
        stage_idx = min(t.revision_count, len(stages) - 1)
        state.memo_wall[thread_id] = stages[stage_idx]


def _update_exit_ready(state: GameState, thread_id: str):
    t = _thread(state, thread_id)
    thresh = content.EXIT_READY_THRESHOLD
    if (not t.exit_ready and t.observation_count >= thresh["observations"]
            and t.revision_count >= thresh["revisions"]):
        t.exit_ready = True
        state.exit_unlocked = True
        _log(state, "이젠 여기서 더 볼 수 있는 게 없는 것 같다. 직접 나가서 확인해야 할 것 같다.")


# ------------------------------------------------------------------ 상호작용 진입점
def interact(state: GameState, object_id: str, action_id: str,
             player_pos: tuple[int, int] | None = None, **payload) -> dict:
    if state.ended:
        raise GameError("이미 끝난 세션이야.", 409)
    if player_pos is not None and object_id != "records":  # 기록 화면은 방 안 물건이 아니다
        px, py = player_pos
        if not content.is_adjacent_to_object(px, py, object_id):
            raise GameError("그 오브젝트 근처로 가야 상호작용할 수 있어.", 400)

    handler = _HANDLERS.get((object_id, action_id))
    if handler is None:
        raise GameError("알 수 없는 상호작용이야.", 404)

    result = handler(state, payload)
    _record_interaction(state, object_id, action_id)
    return result


# ------------------------------------------------------------------ 창문
def _h_window_glance(state: GameState, payload: dict) -> dict:
    if _check_zero_turn(state, "window"):
        text = content.ENOUGH_ALREADY["window"]
    else:
        text = random.choice(content.WINDOW_GLANCE_LINES)
    _log(state, text)
    return {"text": text, "turn_cost": 0}


# 기존 세계 이벤트(밤바다 불빛/낮달/아저씨 귀환/고양이)는 세계의 사실(world.py)이 그 시점에 실제로
# 일어나고 있을 때, 그 대상을 처음 본 그날 한 번 우선해서 보여준다. 그 외에는 148문장 후보에서 고른다.
def _legacy_world_event(state: GameState, subject: str | None) -> dict | None:
    day, period, seed = state.day, state.period, state.seed
    looks_at = (lambda s: subject in (None, window.ANY, s))
    seen_today = (lambda thread: any(o.thread == thread and o.day == day for o in state.observations))

    if looks_at("sea") and world.night_light_visible(day, period, seed) and not seen_today("night_light"):
        t = _thread(state, "night_light")
        variant = world.night_light_variant(day, seed)
        if t.observation_count == 0:
            text = "밤바다 오른쪽에서 흰 불빛을 봤다. 깜빡이는 것처럼 보인다."
            tags = {"color": "white", "side": variant["side"], "blinking": variant["blinking"]}
        else:
            stage_idx = min(t.observation_count, len(content.NIGHT_LIGHT_REOBSERVE_STAGES) - 1)
            text = content.NIGHT_LIGHT_REOBSERVE_STAGES[stage_idx]
            tags = {"blinking": variant["blinking"], "angle_shift": variant["angle_shift"]}
        obs = _add_observation(state, "night_light", text, tags)
        return {"text": text, "subject": "sea", "thread": "night_light", "observation_id": obs.id}

    if looks_at("sky") and world.daytime_moon_visible(day, period, seed) and not seen_today("moon"):
        t = _thread(state, "moon")
        if t.observation_count == 0:
            text = "낮 하늘에 달이 떠 있다. '달? 낮인데.' 하는 생각이 스쳤다."
        else:
            text = "왜 낮에 달이 있으면 이상하다고 생각했는지, 그 생각이 계속 맴돈다."
        obs = _add_observation(state, "moon", text)
        window.record_facts(state, window_content.LEGACY_EFFECTS["moon"] + ["subject:sky"])
        return {"text": text, "subject": "sky", "thread": "moon", "observation_id": obs.id}

    if looks_at("shop") and period in ("day1", "day2") and world.owner_returns_at_day(day, seed):
        key = f"rest_return|{day}"
        if key not in state.zero_turn_counts:  # 하루에 한 번만 기록
            state.zero_turn_counts[key] = 1
            status = world.restaurant_status(day, period, seed)
            text = "아저씨가 상자를 들고 돌아왔다."  # 못 본 출항은 적지 않는다 (관찰한 것만 기록)
            obs = _add_observation(state, "restaurant", text, {"status": status})
            window.record_facts(state, window_content.LEGACY_EFFECTS["restaurant"] + ["subject:shop"])
            return {"text": text, "subject": "shop", "thread": "restaurant", "observation_id": obs.id}

    if looks_at("yard") and world.cat_in_yard(day, period, seed) and "cat" not in state.fact_flags:
        # 처음 보는 고양이. 이 관찰이 있어야 YARD-001("지난번에도 저 길로...")이 출현할 수 있다.
        text = content.MEANINGLESS_WINDOW_EVENTS["cat"]
        window.record_facts(state, window_content.LEGACY_EFFECTS["cat"] + ["subject:yard"])
        return {"text": text, "subject": "yard", "thread": None}
    return None


def _window_observe_world(state: GameState, subject: str | None) -> dict:
    legacy = _legacy_world_event(state, subject)
    if legacy is not None:
        legacy.setdefault("observation_key", None)
        return legacy

    picked_subject, obs = window.pick(state, subject)
    if obs is None:
        # WIN-05: 후보가 없으면 중립 문장. 턴은 쓰지만 발견/지식/질문은 만들지 않는다.
        return {"text": window_content.NEUTRAL_LINES.get(picked_subject, window_content.NEUTRAL_LINES[None]),
                "subject": picked_subject, "thread": None, "observation_key": None}
    rec = window.record_observation(state, obs)
    # 자동 연결: 이번 관찰까지 반영된 기록으로 판정. 문장은 하나의 일지 항목으로 합치고,
    # 내부 기록은 observation_history 와 state.connections 에 따로 남는다. 턴 비용은 그대로.
    conn = connection.check(state, obs)
    text = obs["text"] if conn is None else f"{obs['text']} {conn['text']}"
    return {
        "text": window.render_text(state, text),
        "subject": obs["subject"],
        "thread": None,
        "observation_key": obs["id"],
        "first_seen": rec["first_seen"],
        "new_knowledge": rec["new_knowledge"],
        "connection": None if conn is None else {"id": conn["id"], "stage": conn["stage"]},
        # QST-01: 이 관찰로 처음 떠오른 준비된 질문 (등록 여부는 플레이어가 고른다)
        "questions": inquiry.raise_questions(state, obs["id"]),
    }


_LEGACY_REGION_ALIASES = {"restaurant": "shop"}


def _h_window_observe(state: GameState, payload: dict) -> dict:
    """WIN-01~03: 대상(바다/하늘/횟집/강아지/앞마당) 또는 그냥 바라본다(any). 실행마다 정확히 1턴."""
    subject = payload.get("subject", payload.get("region"))
    subject = _LEGACY_REGION_ALIASES.get(subject, subject)
    if subject not in (None, window.ANY, *window_content.SUBJECTS):
        raise GameError("그쪽은 창문에서 보이지 않아.", 400)
    result = _window_observe_world(state, subject)
    _log(state, result["text"])
    result["turn_cost"] = 1
    _advance_time(state, 1)  # 시간대/날짜 갱신은 기존 규칙에 위임 (WIN-04)
    return result


def _h_window_zoom(state: GameState, payload: dict) -> dict:
    """쌍안경으로 확대 관찰. 맨눈으로 먼저 위치를 확인해야 하고(관찰 방법의 확장이지 능력치 증가가 아님),
    첫 사용에서는 좁은 시야 때문에 대상을 놓쳤다가 다시 맞추는 연출을 한 번 거친다."""
    if "쌍안경" not in state.items:
        raise GameError("쌍안경이 없다.", 400)

    day, period, seed = state.day, state.period, state.seed
    if not world.night_light_visible(day, period, seed):
        text = "쌍안경을 들었지만 지금은 딱히 당겨 볼 게 없다."
        _log(state, text)
        _advance_time(state, 1)
        return {"text": text, "turn_cost": 1, "thread": None}

    t = _thread(state, "night_light")
    if t.observation_count == 0:
        # 맨눈 관찰 없이 쌍안경부터 들이댄 경우 - 위치를 몰라서 놓친다.
        text = content.BINOCULARS_NEED_NAKED_EYE_FIRST
        _log(state, text)
        _advance_time(state, 1)
        return {"text": text, "thread": None, "turn_cost": 1}

    prefix = ""
    if not state.binoculars_fumbled_once:
        prefix = content.BINOCULARS_FIRST_USE_FUMBLE
        state.binoculars_fumbled_once = True

    text = prefix + content.NIGHT_LIGHT_ZOOM_TEXT
    obs = _add_observation(state, "night_light", text, {"zoomed": True})
    _log(state, text)
    _advance_time(state, 1)
    return {"text": text, "turn_cost": 1, "thread": "night_light", "observation_id": obs.id}


# ------------------------------------------------------------------ 파도 주기 탐구 (2차, 5장)
def _h_window_wave(action):
    def handler(state: GameState, payload: dict) -> dict:
        if not inquiry.wave_available(state):
            raise GameError("아직 파도를 눈여겨본 적이 없다.", 400)
        result = action(state)
        _log(state, result["text"])
        _advance_time(state, result["turn_cost"])
        return result
    return handler


def _h_desk_wave_interpret(state: GameState, payload: dict) -> dict:
    try:
        result = inquiry.wave_interpret(state, payload.get("choice"))
    except ValueError as e:
        raise GameError(str(e), 400)
    _log(state, result["text"])
    _advance_time(state, result["turn_cost"])
    return result


# ------------------------------------------------------------------ 기록 화면 (REC-03: 0턴)
def _h_records_question(state: GameState, payload: dict) -> dict:
    try:
        r = inquiry.set_question_state(state, payload.get("question"), payload.get("question_state"))
    except ValueError as e:
        raise GameError(str(e), 400)
    return {**r, "turn_cost": 0}


# ------------------------------------------------------------------ 이삿짐 상자
def _h_boxes_organize(state: GameState, payload: dict) -> dict:
    count = sum(1 for e in state.interaction_log if e["object"] == "boxes" and e["action"] == "organize")
    if count >= len(content.BOX_REVEALS):
        text = "더 정리할 건 없어 보인다."
        _log(state, text)
        _advance_time(state, 1)
        return {"text": text, "turn_cost": 1}

    text = content.BOX_REVEALS[count]
    _log(state, text)
    discovery = None
    if count == content.BINOCULARS_REVEAL_INDEX and "쌍안경" not in state.items:
        state.items.append("쌍안경")
        discovery = Discovery(id=_new_id(), day=state.day, period=state.period, text="쌍안경을 챙겼다.")
        state.discoveries.append(discovery)
    _advance_time(state, 1)
    return {"text": text, "turn_cost": 1, "item_gained": "쌍안경" if discovery else None}


# ------------------------------------------------------------------ 책상
def _h_desk_hypothesis(state: GameState, payload: dict) -> dict:
    thread_id = payload.get("thread")
    template_idx = payload.get("template_index")
    if thread_id not in content.HYPOTHESIS_TEMPLATES:
        raise GameError("이 주제는 아직 가설을 세울 만한 관찰이 없어.", 400)
    t = _thread(state, thread_id)
    if t.observation_count == 0:
        raise GameError("먼저 관찰부터 해야 가설을 세울 수 있어.", 400)

    existing = next((h for h in state.hypotheses if h.thread == thread_id), None)
    templates = content.HYPOTHESIS_TEMPLATES[thread_id]
    if existing is None:
        idx = template_idx if isinstance(template_idx, int) and 0 <= template_idx < len(templates) else 0
        tpl = templates[idx]
        hyp = Hypothesis(id=_new_id(), thread=thread_id, created_day=state.day,
                          text=tpl["text"], status=tpl["status"],
                          revisions=[HypothesisRevision(day=state.day, period=state.period,
                                                         status=tpl["status"], text=tpl["text"])])
        state.hypotheses.append(hyp)
        text = f"책상 앞에 앉아 가설을 적었다: “{tpl['text']}”"
    else:
        # 반례에 따른 재검토. 자동으로 제안되는 재검토 문구를 그대로 쓴다 (틀린 가설은 지우지 않는다).
        reason = "재관찰 결과와 맞지 않는 부분이 있다."
        stage_idx = min(t.revision_count, len(content.NIGHT_LIGHT_REOBSERVE_STAGES) - 1) \
            if thread_id == "night_light" else 0
        revised_text = (
            f"{existing.latest_text()} — 위치 변화 확인 못 함. 재검토 필요."
            if t.revision_count == 0 else
            f"{existing.latest_text()} (계속 지켜보는 중)"
        )
        existing.revisions.append(HypothesisRevision(
            day=state.day, period=state.period, status="revised", text=revised_text, reason=reason,
        ))
        existing.status = "revised"
        t.revision_count += 1
        text = f"책상에서 기존 가설을 다시 봤다: “{revised_text}”"
        hyp = existing
        _update_memo_wall(state, thread_id)
        _update_exit_ready(state, thread_id)

    _log(state, text)
    _advance_time(state, 1)
    return {"text": text, "turn_cost": 1, "hypothesis_id": hyp.id}


# ------------------------------------------------------------------ 컴퓨터
def _h_computer_search(state: GameState, payload: dict) -> dict:
    thread_id = payload.get("thread", "night_light")
    spec = content.COMPUTER_SEARCHES.get(thread_id)
    if spec is None:
        raise GameError("그 주제로는 아직 검색할 게 마땅치 않아.", 400)
    if spec["requires_observation"] and _thread(state, thread_id).observation_count == 0:
        raise GameError("직접 본 게 있어야 뭘 검색할지 떠오를 것 같아.", 400)

    state.searches.append({"query": spec["query"], "day": state.day, "period": state.period})
    added = []
    for cand in spec["candidates"]:
        info = InfoEntry(id=_new_id(), thread=thread_id, day=state.day, source="computer", text=cand)
        state.infos.append(info)
        added.append(cand)
    text = f"'{spec['query']}'(으)로 검색했다. 여러 후보가 나왔다."
    _log(state, text)
    _advance_time(state, 1)
    return {"text": text, "turn_cost": 1, "candidates": added}


# ------------------------------------------------------------------ 책장
def _h_bookshelf_skim(state: GameState, payload: dict) -> dict:
    if _check_zero_turn(state, "bookshelf"):
        text = content.ENOUGH_ALREADY["bookshelf"]
    else:
        text = content.BOOKSHELF_GLANCE
    _log(state, text)
    return {"text": text, "turn_cost": 0}


def _h_bookshelf_read(state: GameState, payload: dict) -> dict:
    for thread_id, book in content.BOOKSHELF_UNLOCKED_BOOKS.items():
        if thread_id in state.books_read:
            continue
        n_infos = sum(1 for i in state.infos if i.thread == thread_id and i.source == "computer")
        if n_infos >= book["trigger_min_infos"] and (
            book["trigger_min_infos"] > 0 or _thread(state, thread_id).observation_count > 0
        ):
            state.books_read.append(thread_id)
            info = InfoEntry(id=_new_id(), thread=thread_id, day=state.day, source="book", text=book["info_text"])
            state.infos.append(info)
            text = book["text"]
            _log(state, text)
            _advance_time(state, 1)
            return {"text": text, "turn_cost": 1, "info_gained": book["info_text"]}

    book = inquiry.read_wave_book(state)  # 2차: 파도를 눈여겨본 뒤에 눈에 들어오는 책 (지식 '접함')
    if book is not None:
        _log(state, book["text"])
        _advance_time(state, 1)
        return {"text": book["text"], "turn_cost": 1, "info_gained": book["info_gained"],
                "new_knowledge": [book["knowledge"]]}

    text = random.choice(content.BOOKSHELF_GENERIC_BOOKS)
    _log(state, text)
    _advance_time(state, 1)
    return {"text": text, "turn_cost": 1}


# ------------------------------------------------------------------ 보조 오브젝트
def _h_mirror_glance(state: GameState, payload: dict) -> dict:
    text = content.ENOUGH_ALREADY["mirror"] if _check_zero_turn(state, "mirror") else random.choice(content.MIRROR_LINES)
    _log(state, text)
    return {"text": text, "turn_cost": 0}


def _h_bed_lie_down(state: GameState, payload: dict) -> dict:
    text = random.choice(content.BED_LINES)
    _log(state, text)
    return {"text": text, "turn_cost": 0}


def _h_radio_listen(state: GameState, payload: dict) -> dict:
    if _check_zero_turn(state, "radio"):
        text = content.ENOUGH_ALREADY["radio"]
    else:
        _, text = random.choice(content.RADIO_LINES)
    _log(state, text)
    return {"text": text, "turn_cost": 0}


def _h_mail_check(state: GameState, payload: dict) -> dict:
    if state.day < 2:
        text = content.MAIL_LINES_BEFORE
    else:
        text = random.choice(content.MAIL_LINES_FLAVOR)
    _log(state, text)
    return {"text": text, "turn_cost": 0}


def _h_memowall_check(state: GameState, payload: dict) -> dict:
    if _check_zero_turn(state, "memowall"):
        text = content.ENOUGH_ALREADY["memowall"]
        questions = list(state.memo_wall.values())
    elif not state.memo_wall:
        text = "메모벽은 아직 비어 있다."
        questions = []
    else:
        questions = list(state.memo_wall.values())
        text = "메모벽을 봤다. " + " / ".join(questions)
    _log(state, text)
    return {"text": text, "turn_cost": 0, "questions": questions}


def _h_door_exit(state: GameState, payload: dict) -> dict:
    if not state.exit_unlocked:
        text = "나가서 뭐 하지."
        _log(state, text)
        return {"text": text, "turn_cost": 0, "ended": False}
    state.ended = True
    text = "문을 열고 밖으로 나섰다."
    _log(state, text)
    return {"text": text, "turn_cost": 0, "ended": True, "end_label": "Vertical Slice End"}


_HANDLERS = {
    ("window", "glance"): _h_window_glance,
    ("window", "observe"): _h_window_observe,
    ("window", "zoom"): _h_window_zoom,
    ("window", "wave_count"): _h_window_wave(inquiry.wave_count),
    ("window", "wave_measure"): _h_window_wave(inquiry.wave_measure),
    ("desk", "wave_interpret"): _h_desk_wave_interpret,
    ("records", "question"): _h_records_question,
    ("boxes", "organize"): _h_boxes_organize,
    ("desk", "hypothesis"): _h_desk_hypothesis,
    ("computer", "search"): _h_computer_search,
    ("bookshelf", "skim"): _h_bookshelf_skim,
    ("bookshelf", "read"): _h_bookshelf_read,
    ("mirror", "glance"): _h_mirror_glance,
    ("bed", "lie_down"): _h_bed_lie_down,
    ("radio", "listen"): _h_radio_listen,
    ("mail", "check"): _h_mail_check,
    ("memowall", "check"): _h_memowall_check,
    ("door", "exit"): _h_door_exit,
}


# ------------------------------------------------------------------ 화면용 스냅샷
def snapshot(state: GameState) -> dict:
    return {
        "day": state.day,
        "period": state.period,
        "period_label": PERIOD_LABELS.get(state.period, state.period),
        "map": content.ROOM_MAP,
        "objects": content.OBJECTS,
        "object_tiles": content.OBJECT_TILES,
        "player_start": list(content.PLAYER_START),
        "items": list(state.items),
        "memo_wall": list(state.memo_wall.values()),
        "window_resolution": state.window_resolution,
        "window": {
            "subjects": [{"id": sid, "label": window.subject_label(state, sid)}
                         for sid in window_content.SUBJECTS],
            "seen": len(state.observation_history),
            "total": len(window_content.OBSERVATIONS),
            "knowledge_count": len(state.knowledge),
        },
        "wave": inquiry.wave_view(state),
        "records": inquiry.records_view(state),
        "exit_unlocked": state.exit_unlocked,
        "ended": state.ended,
        "log": state.log[-30:],
        "desk": _desk_view(state),
        "hypothesis_templates": content.HYPOTHESIS_TEMPLATES,
        "computer_searches": {tid: {"query": spec["query"]} for tid, spec in content.COMPUTER_SEARCHES.items()},
    }


def _desk_view(state: GameState) -> dict:
    threads = {}
    for obs in state.observations:
        threads.setdefault(obs.thread, {"observations": [], "infos": [], "hypothesis": None})
        threads[obs.thread]["observations"].append({"day": obs.day, "period": obs.period, "text": obs.text})
    for info in state.infos:
        threads.setdefault(info.thread, {"observations": [], "infos": [], "hypothesis": None})
        threads[info.thread]["infos"].append({"source": info.source, "text": info.text})
    for hyp in state.hypotheses:
        threads.setdefault(hyp.thread, {"observations": [], "infos": [], "hypothesis": None})
        threads[hyp.thread]["hypothesis"] = {
            "text": hyp.latest_text(), "status": hyp.status,
            "status_label": HYPOTHESIS_STATUS_LABELS.get(hyp.status, hyp.status),
            "history": [asdict(r) for r in hyp.revisions],
        }
    return threads
