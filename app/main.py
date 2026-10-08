"""
FastAPI 서버. 방 이동/렌더링은 클라이언트(JS)가 맡고, 서버는 시간/세계상태/관찰-가설 같은
게임 로직의 '진실 공급원(source of truth)' 역할만 한다.

실행:
    uvicorn app.main:app --reload --port 8000
"""
from __future__ import annotations

import uuid
from pathlib import Path

from fastapi import FastAPI, Request, Response
from fastapi.responses import HTMLResponse, JSONResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates

from .engine import engine, storage
from .engine.engine import GameError
from .engine.models import GameState

BASE_DIR = Path(__file__).resolve().parent
app = FastAPI(title="INTP 키우기 - 방 Vertical Slice")
app.mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static")
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))

# 방-에디터 프런트엔드(room_editor/, build.py가 만든 정적 index.html).
# 가구 위치/캐릭터 이동/상호작용 UI는 전부 여기 들어있고, 서버는 /api/*만 제공한다.
# base64로 인코딩된 이미지가 대량으로 들어있어 Jinja2 템플릿을 거치지 않고(오탐 방지) 그대로 돌려준다.
ROOM_EDITOR_HTML = (BASE_DIR.parent / "room_editor" / "index.html").read_text(encoding="utf-8")

SESSION_COOKIE = "intp_room_session"
_live_states: dict[str, GameState] = {}  # 프로세스 메모리 캐시 (세이브 파일이 진짜 소스)


def _get_session_id(request: Request) -> str:
    sid = request.cookies.get(SESSION_COOKIE)
    if not sid:
        sid = uuid.uuid4().hex[:16]
    return sid


def _get_state(session_id: str) -> GameState:
    if session_id in _live_states:
        return _live_states[session_id]
    loaded = storage.load(session_id)
    state = loaded if loaded is not None else engine.new_game()
    _live_states[session_id] = state
    return state


def _with_session_cookie(response: Response, session_id: str) -> Response:
    response.set_cookie(SESSION_COOKIE, session_id, max_age=60 * 60 * 24 * 365, httponly=True, samesite="lax")
    return response


@app.get("/", response_class=HTMLResponse)
def index(request: Request):
    session_id = _get_session_id(request)
    _get_state(session_id)  # 세션 쿠키를 심어 /api/state가 같은 게임을 이어받게 한다
    resp = HTMLResponse(ROOM_EDITOR_HTML)
    return _with_session_cookie(resp, session_id)


@app.get("/api/state")
def api_state(request: Request):
    session_id = _get_session_id(request)
    state = _get_state(session_id)
    resp = JSONResponse(engine.snapshot(state))
    return _with_session_cookie(resp, session_id)


@app.post("/api/new")
def api_new(request: Request):
    session_id = _get_session_id(request)
    state = engine.new_game()
    _live_states[session_id] = state
    storage.save(session_id, state)
    resp = JSONResponse(engine.snapshot(state))
    return _with_session_cookie(resp, session_id)


@app.post("/api/interact")
async def api_interact(request: Request):
    session_id = _get_session_id(request)
    state = _get_state(session_id)
    body = await request.json()
    object_id = body.get("object")
    action_id = body.get("action")
    player_pos = tuple(body["player_pos"]) if body.get("player_pos") else None
    payload = {k: v for k, v in body.items() if k not in ("object", "action", "player_pos")}

    try:
        result = engine.interact(state, object_id, action_id, player_pos=player_pos, **payload)
    except GameError as e:
        resp = JSONResponse({"error": e.message}, status_code=e.code)
        return _with_session_cookie(resp, session_id)

    storage.save(session_id, state)
    resp = JSONResponse({"result": result, "snapshot": engine.snapshot(state)})
    return _with_session_cookie(resp, session_id)


@app.post("/api/save")
def api_save(request: Request):
    session_id = _get_session_id(request)
    state = _get_state(session_id)
    storage.save(session_id, state)
    resp = JSONResponse({"ok": True})
    return _with_session_cookie(resp, session_id)


@app.post("/api/load")
def api_load(request: Request):
    session_id = _get_session_id(request)
    loaded = storage.load(session_id)
    if loaded is None:
        resp = JSONResponse({"error": "저장된 게임이 없어."}, status_code=404)
        return _with_session_cookie(resp, session_id)
    _live_states[session_id] = loaded
    resp = JSONResponse(engine.snapshot(loaded))
    return _with_session_cookie(resp, session_id)
