"""assets/character/*.png(투명 배경 캐릭터 스프라이트)을 base64로 묶어
src/character-sprites.js를 (다시) 생성한다.

캐릭터 원본 PNG을 교체하거나 포즈를 추가/삭제했을 때 이 스크립트를 다시 실행한다.
사용법: python tools/build_character_sprites.py
"""
import base64
from pathlib import Path

ROOT = Path(__file__).parent.parent
ASSETS = ROOT / "assets" / "character"
OUT = ROOT / "src" / "character-sprites.js"

IDLE_DIRS = ["front", "right", "back", "left"]
WALK_DIRS = ["front", "right", "back", "left"]
WALK_FRAMES = 4


def b64(path: Path) -> str:
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode()


def main():
    idle = {d: b64(ASSETS / f"idle_{d}.png") for d in IDLE_DIRS}
    walk = {d: [b64(ASSETS / f"walk_{d}_{i}.png") for i in range(1, WALK_FRAMES + 1)] for d in WALK_DIRS}

    lines = [
        "/* ===== 캐릭터 스프라이트 (업로드한 참고 시트에서 추출, base64 내장) =====",
        " * 이 파일은 assets/character/*.png을 base64로 담은 생성 파일이다.",
        " * 원본 PNG을 바꾼 뒤에는 tools/build_character_sprites.py를 다시 실행해 갱신한다. */",
        "window.INTP_CHARACTER_SPRITES = {",
        "  idle: {",
    ]
    for k, v in idle.items():
        lines.append(f'    {k}: "{v}",')
    lines.append("  },")
    lines.append("  walk: {")
    for k, v in walk.items():
        arr = ",\n      ".join(f'"{x}"' for x in v)
        lines.append(f"    {k}: [\n      {arr}\n    ],")
    lines.append("  }")
    lines.append("};")

    OUT.write_text("\n".join(lines), encoding="utf-8")
    print(f"built {OUT.relative_to(ROOT)}: {OUT.stat().st_size:,} bytes")


if __name__ == "__main__":
    main()
