"""세이브 파일 읽기/쓰기. 세션별 JSON 파일, 임시 파일 후 교체(원자적 저장)."""
from __future__ import annotations

import json
from pathlib import Path

from .models import GameState, state_to_dict, state_from_dict

SAVE_DIR = Path(__file__).resolve().parent.parent / "data" / "saves"
SAVE_DIR.mkdir(parents=True, exist_ok=True)


def _path(session_id: str) -> Path:
    safe = "".join(c for c in session_id if c.isalnum() or c in "-_") or "default"
    return SAVE_DIR / f"{safe}.json"


def save(session_id: str, state: GameState) -> None:
    target = _path(session_id)
    tmp = target.with_suffix(".tmp")
    tmp.write_text(json.dumps(state_to_dict(state), ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(target)


def load(session_id: str) -> GameState | None:
    p = _path(session_id)
    if not p.exists():
        return None
    try:
        data = json.loads(p.read_text(encoding="utf-8"))
        return state_from_dict(data)
    except Exception:
        return None
