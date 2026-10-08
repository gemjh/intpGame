"""원본 HTML(source-261008.html)에 편집기 CSS/JS를 주입해 index.html을 만든다.

원본은 수정하지 않는다. 교체할 가구 이미지는 IMAGE_OVERRIDES에 등록한다.
사용법:  python build.py
"""
import base64
import re
import struct
from pathlib import Path

ROOT = Path(__file__).parent
SOURCE = ROOT / "source-261008.html"
OUT = ROOT / "index.html"

# 가구 id -> 새 이미지(투명 배경 PNG). 화면에서 차지하는 면적은 원본과 같게 유지하고
# 가로·세로는 새 이미지 비율에 맞춰 다시 계산한다.
IMAGE_OVERRIDES = {
    "mirror": ROOT / "assets" / "mirror-new.png",
    "window": ROOT / "assets" / "window-new.png",
    "board": ROOT / "assets" / "board-new.png",
    "desk": ROOT / "assets" / "desk-new.png",
}
ROOM_W, ROOM_H = 1200, 800


def png_size(data: bytes):
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "PNG 파일만 지원합니다"
    return struct.unpack(">II", data[16:24])


def override_image(html: str, key: str, path: Path) -> str:
    pat = re.compile(
        r'(<button class="furniture" data-key="' + re.escape(key) + r'" style=")([^"]*)("[^>]*><img draggable="false" src=")(data:image/[a-z]+;base64,[A-Za-z0-9+/=]+)(")'
    )
    m = pat.search(html)
    assert m, f"{key} 가구를 원본에서 찾지 못했습니다"
    old = base64.b64decode(m.group(4).split(",", 1)[1])
    ow, oh = png_size(old)
    new = path.read_bytes()
    nw, nh = png_size(new)
    style = m.group(2)
    width_pct = float(re.search(r"width:([\d.]+)%", style).group(1))
    # 원본 화면 면적(room px²)을 유지하는 새 너비 계산
    old_w = width_pct / 100 * ROOM_W
    area = old_w * old_w * oh / ow
    new_width_pct = (area * nw / nh) ** 0.5 / ROOM_W * 100
    style = re.sub(r"width:[\d.]+%", f"width:{new_width_pct:.2f}%", style)
    src = "data:image/png;base64," + base64.b64encode(new).decode()
    print(f"  {key}: {ow}x{oh} -> {nw}x{nh}, width {width_pct}% -> {new_width_pct:.2f}%")
    return html[: m.start()] + m.group(1) + style + m.group(3) + src + m.group(5) + html[m.end():]


CSS_FILES = ["editor.css", "game.css", "game-interact.css"]
# fixed-layout.js는 editor.js가 초기화 시 읽으므로 반드시 먼저 온다.
# character-sprites.js는 game.js가 참조하므로 game.js보다 먼저 온다(둘 다 window.IntpLayout 이후면 됨).
# game.js는 window.IntpLayout(editor.js가 노출)을 사용하므로 반드시 나중에 온다.
# game-interact.js는 window.IntpPlayer/IntpLayout을 모두 쓰므로 맨 마지막.
JS_FILES = ["fixed-layout.js", "editor.js", "character-sprites.js", "game.js", "game-interact.js"]

html = SOURCE.read_text(encoding="utf-8")
css = "\n".join((ROOT / "src" / f).read_text(encoding="utf-8") for f in CSS_FILES)
js = "\n".join((ROOT / "src" / f).read_text(encoding="utf-8") for f in JS_FILES)

assert html.count("</head>") == 1 and html.count("</body>") == 1, "원본 구조가 예상과 다릅니다"
for key, path in IMAGE_OVERRIDES.items():
    html = override_image(html, key, path)
html = html.replace("</head>", f"<style>\n{css}</style></head>")
html = html.replace("</body>", f"<script>\n{js}</script></body>")

OUT.write_text(html, encoding="utf-8")
print(f"built {OUT.name}: {OUT.stat().st_size:,} bytes")
