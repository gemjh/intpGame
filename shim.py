"""
이 샌드박스엔 PyPI 접근 제한으로 fastapi 가 안 깔려 있어서(starlette/uvicorn/jinja2는 있음),
app/main.py 와 100% 같은 템플릿/정적파일/엔진 코드를 Starlette 로 그대로 띄워 로컬 검증용으로만 쓰는 shim.
실제 배포본은 app/main.py (FastAPI) 를 쓰면 된다.
"""
import sys
import uuid
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from starlette.applications import Starlette
from starlette.responses import HTMLResponse, JSONResponse
from starlette.routing import Route, Mount
from starlette.staticfiles import StaticFiles
from starlette.templating import Jinja2Templates

from app.engine import engine, storage
from app.engine.engine import GameError

BASE_DIR = Path(__file__).resolve().parent / "app"
templates = Jinja2Templates(directory=str(BASE_DIR / "templates"))
ROOM_EDITOR_HTML = (Path(__file__).resolve().parent / "room_editor" / "index.html").read_text(encoding="utf-8")

SESSION_COOKIE = "intp_room_session"
_live_states = {}


def _get_session_id(request):
    sid = request.cookies.get(SESSION_COOKIE)
    return sid or uuid.uuid4().hex[:16]


def _get_state(session_id):
    if session_id in _live_states:
        return _live_states[session_id]
    loaded = storage.load(session_id)
    state = loaded if loaded is not None else engine.new_game()
    _live_states[session_id] = state
    return state


async def index(request):
    session_id = _get_session_id(request)
    _get_state(session_id)
    resp = HTMLResponse(ROOM_EDITOR_HTML)
    resp.set_cookie(SESSION_COOKIE, session_id)
    return resp


async def api_state(request):
    session_id = _get_session_id(request)
    state = _get_state(session_id)
    resp = JSONResponse(engine.snapshot(state))
    resp.set_cookie(SESSION_COOKIE, session_id)
    return resp


async def api_new(request):
    session_id = _get_session_id(request)
    state = engine.new_game()
    _live_states[session_id] = state
    storage.save(session_id, state)
    resp = JSONResponse(engine.snapshot(state))
    resp.set_cookie(SESSION_COOKIE, session_id)
    return resp


async def api_interact(request):
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
        resp.set_cookie(SESSION_COOKIE, session_id)
        return resp
    storage.save(session_id, state)
    resp = JSONResponse({"result": result, "snapshot": engine.snapshot(state)})
    resp.set_cookie(SESSION_COOKIE, session_id)
    return resp


async def api_save(request):
    session_id = _get_session_id(request)
    state = _get_state(session_id)
    storage.save(session_id, state)
    resp = JSONResponse({"ok": True})
    resp.set_cookie(SESSION_COOKIE, session_id)
    return resp


routes = [
    Route("/", index),
    Route("/api/state", api_state),
    Route("/api/new", api_new, methods=["POST"]),
    Route("/api/interact", api_interact, methods=["POST"]),
    Route("/api/save", api_save, methods=["POST"]),
    Mount("/static", StaticFiles(directory=str(BASE_DIR / "static")), name="static"),
]

app = Starlette(routes=routes)
