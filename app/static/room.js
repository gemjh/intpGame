// 방 Vertical Slice 클라이언트. 이동/충돌은 여전히 격자(그리드) 좌표 기준이고, 그 위에 아이소메트릭
// 화면 변환을 입혀서 그리는 것뿐이다. 게임 로직(시간/관찰/가설)은 서버가 진실공급원 — 여기선 안 바뀜.
(function () {
  "use strict";

  const RADIUS = 0.32;
  const SPEED = 4.4; // tiles / sec

  // ------------------------------------------------------------------ 아이소메트릭 투영
  const TW = 56; // 타일 다이아몬드 가로
  const TH = 28; // 타일 다이아몬드 세로
  const WALL_H = 86; // 벽 높이(px)
  const ORIGIN_X = 300;
  const ORIGIN_Y = 118;

  function isoX(tx, ty) {
    return ORIGIN_X + (tx - ty) * (TW / 2);
  }
  function isoY(tx, ty) {
    return ORIGIN_Y + (tx + ty) * (TH / 2);
  }

  let snap = window.__SNAPSHOT__;
  let mapRows = snap.map;
  let objectTiles = snap.object_tiles;
  let objectDefs = {}; // id -> {name, role, tier}
  Object.values(snap.objects).forEach((o) => (objectDefs[o.id] = o));

  const ROOM_COLS = mapRows[0].length;
  const ROOM_ROWS = mapRows.length;

  const canvas = document.getElementById("room");
  const ctx = canvas.getContext("2d");
  const interactPanel = document.getElementById("interact-panel");

  const player = { x: snap.player_start[0] + 0.5, y: snap.player_start[1] + 0.5 };
  let facing = "down";
  const keys = {};
  let lastPanelObjectId = null; // 상호작용 패널을 매 프레임 다시 그리지 않기 위한 캐시

  // ------------------------------------------------------------------ 스프라이트 (itch.io "Isometric pixel bedroom" by Sweeetpotatoo, free to use)
  function loadSprite(path) {
    const img = new Image();
    img.loaded = false;
    img.onload = () => (img.loaded = true);
    img.src = path;
    return img;
  }
  const SPR = {
    bed: loadSprite("/static/sprites/bed.png"),
    bookshelf: loadSprite("/static/sprites/bookshelf.png"),
    desk_full: loadSprite("/static/sprites/desk_full.png"),
    desk_plain: loadSprite("/static/sprites/desk_plain.png"),
    mirror: loadSprite("/static/sprites/mirror.png"),
    box: loadSprite("/static/sprites/box1.png"),
    rug: loadSprite("/static/sprites/rug.png"),
  };

  // ------------------------------------------------------------------ 입력
  window.addEventListener("keydown", (e) => {
    keys[e.key] = true;
    if (["ArrowUp", "ArrowDown", "ArrowLeft", "ArrowRight", " "].includes(e.key)) e.preventDefault();
  });
  window.addEventListener("keyup", (e) => (keys[e.key] = false));

  // ------------------------------------------------------------------ 충돌 (격자 좌표 기준, 화면 투영과 무관)
  function walkable(tx, ty) {
    if (ty < 0 || ty >= mapRows.length) return false;
    const row = mapRows[ty];
    if (tx < 0 || tx >= row.length) return false;
    return row[tx] === ".";
  }

  function canStandAt(x, y) {
    const minX = Math.floor(x - RADIUS), maxX = Math.floor(x + RADIUS);
    const minY = Math.floor(y - RADIUS), maxY = Math.floor(y + RADIUS);
    for (let ty = minY; ty <= maxY; ty++) {
      for (let tx = minX; tx <= maxX; tx++) {
        if (!walkable(tx, ty)) return false;
      }
    }
    return true;
  }

  // ------------------------------------------------------------------ 근접 판정 (서버 is_adjacent_to_object 와 동일 규칙)
  function nearestObject() {
    const px = Math.floor(player.x), py = Math.floor(player.y);
    let best = null, bestDist = Infinity;
    for (const [oid, tiles] of Object.entries(objectTiles)) {
      for (const [ox, oy] of tiles) {
        const d = Math.abs(ox - px) + Math.abs(oy - py);
        if (d <= 1 && d < bestDist) {
          best = oid;
          bestDist = d;
        }
      }
    }
    return best;
  }

  // ------------------------------------------------------------------ 오브젝트별 행동 정의
  const OBJECT_ACTIONS = {
    window: () => {
      const hasBinoculars = snap.items.includes("쌍안경");
      const zoomBtn = { action: "zoom", label: "쌍안경으로 확대해서 보기 (1턴)", cost: 1 };
      const base =
        snap.window_resolution >= 1
          ? [
              { action: "glance", label: "잠깐 보기 (0턴)", cost: 0 },
              { action: "observe", label: "바다 관찰하기", cost: 1, payload: { region: "sea" } },
              { action: "observe", label: "하늘 관찰하기", cost: 1, payload: { region: "sky" } },
              { action: "observe", label: "앞마당 관찰하기", cost: 1, payload: { region: "yard" } },
              { action: "observe", label: "횟집 관찰하기", cost: 1, payload: { region: "restaurant" } },
            ]
          : [
              { action: "glance", label: "잠깐 보기 (0턴)", cost: 0 },
              { action: "observe", label: "관찰하기 (1턴)", cost: 1, payload: {} },
            ];
      return hasBinoculars ? [...base, zoomBtn] : base;
    },
    boxes: () => [{ action: "organize", label: "정리하기 (1턴)", cost: 1 }],
    desk: () => [{ special: "desk", label: "책상에 앉는다" }],
    computer: () => {
      const q = snap.computer_searches.night_light ? snap.computer_searches.night_light.query : null;
      return [
        {
          action: "search",
          label: q ? `'${q}' 검색하기 (1턴)` : "검색하기 (1턴)",
          cost: 1,
          payload: { thread: "night_light" },
        },
      ];
    },
    bookshelf: () => [
      { action: "skim", label: "책등 훑어보기 (0턴)", cost: 0 },
      { action: "read", label: "집중해서 읽기 (1턴)", cost: 1 },
    ],
    mirror: () => [{ action: "glance", label: "거울 보기 (0턴)", cost: 0 }],
    bed: () => [{ action: "lie_down", label: "누워서 생각하기 (0턴)", cost: 0 }],
    radio: () => [{ action: "listen", label: "라디오 듣기 (0턴)", cost: 0 }],
    mail: () => [{ action: "check", label: "우편물 확인 (0턴)", cost: 0 }],
    memowall: () => [{ special: "memowall", label: "메모벽 보기" }],
    door: () => [
      {
        action: "exit",
        label: snap.exit_unlocked ? "확인하러 간다" : "나간다",
        cost: 0,
      },
    ],
  };

  // ------------------------------------------------------------------ 서버 통신
  async function callInteract(object, action, payload) {
    const body = {
      object,
      action,
      player_pos: [Math.floor(player.x), Math.floor(player.y)],
      ...(payload || {}),
    };
    const res = await fetch("/api/interact", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify(body),
    });
    const data = await res.json();
    if (!res.ok) {
      appendLog("(" + (data.error || "뭔가 안 됐다") + ")");
      return null;
    }
    snap = data.snapshot;
    mapRows = snap.map;
    objectTiles = snap.object_tiles;
    lastPanelObjectId = null; // 다음 프레임에 상호작용 패널을 새로 그려서 변경된 라벨(문/창문 등)을 반영
    refreshChrome();
    if (data.result && data.result.ended) {
      showEndScreen();
    }
    return data.result;
  }

  function appendLog(text) {
    const el = document.getElementById("log");
    const div = document.createElement("div");
    div.className = "line";
    div.textContent = text;
    el.prepend(div);
  }

  function refreshChrome() {
    document.getElementById("day").textContent = snap.day;
    document.getElementById("period").textContent = snap.period_label;
    document.getElementById("items").textContent = snap.items.length ? "소지품: " + snap.items.join(", ") : "";
    const logEl = document.getElementById("log");
    logEl.innerHTML = "";
    [...snap.log].reverse().forEach((line) => {
      const div = document.createElement("div");
      div.className = "line";
      div.textContent = line;
      logEl.appendChild(div);
    });
  }

  function showEndScreen() {
    document.getElementById("end-screen").classList.remove("hidden");
  }

  // ------------------------------------------------------------------ 책상 / 메모벽 모달
  function openDesk() {
    const content = document.getElementById("desk-content");
    content.innerHTML = "";
    const threadIds = Object.keys(snap.desk);
    if (threadIds.length === 0) {
      content.innerHTML = "<p>아직 책상 위에 정리할 게 없다. 뭔가 관찰부터 해야 할 것 같다.</p>";
    }
    threadIds.forEach((tid) => {
      const t = snap.desk[tid];
      const block = document.createElement("div");
      block.className = "thread-block";
      let html = `<h5>관찰</h5>`;
      html += t.observations.length
        ? t.observations.map((o) => `<div class="entry obs">· ${o.text}</div>`).join("")
        : `<div class="entry obs">(아직 없음)</div>`;
      html += `<h5>정보 (책 / 컴퓨터)</h5>`;
      html += t.infos.length
        ? t.infos
            .map((i) => `<div class="entry info">[${i.source === "book" ? "책" : "컴퓨터"}] ${i.text}</div>`)
            .join("")
        : `<div class="entry info">(아직 없음)</div>`;
      html += `<h5>가설 (내 해석)</h5>`;
      if (t.hypothesis) {
        html += `<div class="hyp">${t.hypothesis.text} <span class="hyp-status">[${t.hypothesis.status}]</span></div>`;
      } else {
        html += `<div class="entry">아직 가설이 없다.</div>`;
      }
      block.innerHTML = html;

      const btnRow = document.createElement("div");
      if (!t.hypothesis && t.observations.length > 0) {
        const templates = (snap.hypothesis_templates && snap.hypothesis_templates[tid]) || [];
        templates.forEach((tpl, idx) => {
          const b = document.createElement("button");
          b.textContent = `가설로 적기: "${tpl.text}"`;
          b.onclick = async () => {
            await callInteract("desk", "hypothesis", { thread: tid, template_index: idx });
            openDesk();
          };
          btnRow.appendChild(b);
        });
      } else if (t.hypothesis && t.observations.length > 0) {
        const b = document.createElement("button");
        b.textContent = "책상에서 다시 검토하기";
        b.onclick = async () => {
          await callInteract("desk", "hypothesis", { thread: tid });
          openDesk();
        };
        btnRow.appendChild(b);
      }
      block.appendChild(btnRow);
      content.appendChild(block);
    });
    document.getElementById("desk-modal").classList.remove("hidden");
  }

  function openMemoWall() {
    callInteract("memowall", "check", {}).then(() => {
      const content = document.getElementById("memowall-content");
      content.innerHTML = snap.memo_wall.length
        ? snap.memo_wall.map((q) => `<div class="entry obs">· ${q}</div>`).join("")
        : "<p>메모벽은 아직 비어 있다.</p>";
      document.getElementById("memowall-modal").classList.remove("hidden");
    });
  }

  document.querySelectorAll(".modal-close").forEach((btn) => {
    btn.addEventListener("click", () => {
      document.getElementById(btn.dataset.close).classList.add("hidden");
    });
  });

  document.getElementById("btn-memowall").addEventListener("click", openMemoWall);
  document.getElementById("btn-save").addEventListener("click", async () => {
    await fetch("/api/save", { method: "POST", credentials: "same-origin" });
    appendLog("(저장했다)");
  });
  document.getElementById("btn-new").addEventListener("click", async () => {
    if (!confirm("처음부터 다시 시작할까요?")) return;
    const res = await fetch("/api/new", { method: "POST", credentials: "same-origin" });
    snap = await res.json();
    mapRows = snap.map;
    objectTiles = snap.object_tiles;
    refreshChrome();
    document.getElementById("end-screen").classList.add("hidden");
  });

  // ------------------------------------------------------------------ 오브젝트 화면 앵커(기준점) 계산
  // object_tiles 가 차지하는 칸들의 "앞쪽(남쪽) 가장자리 중앙"을 기준점으로 삼는다 (아이소메트릭에서 자연스러운 받침점).
  function objectAnchorTile(oid) {
    const tiles = objectTiles[oid];
    let minX = Infinity, maxX = -Infinity, minY = Infinity, maxY = -Infinity;
    tiles.forEach(([x, y]) => {
      minX = Math.min(minX, x); maxX = Math.max(maxX, x);
      minY = Math.min(minY, y); maxY = Math.max(maxY, y);
    });
    return { ax: (minX + maxX + 1) / 2, ay: maxY + 1, minX, maxX, minY, maxY };
  }

  // ------------------------------------------------------------------ 상호작용 패널 (근접 시)
  // 매 프레임 다시 그리면 버튼을 누르는 중간에 DOM 이 교체돼 클릭이 씹힌다.
  // 가까운 오브젝트가 "바뀔 때만" 다시 그린다 (lastPanelObjectId 는 위에서 선언).
  function updateInteractPanel() {
    const oid = nearestObject();
    if (!oid) {
      lastPanelObjectId = null;
      interactPanel.classList.add("hidden");
      return;
    }
    if (oid === lastPanelObjectId) return; // 이미 그려진 패널 그대로 둔다 (버튼 보존)
    lastPanelObjectId = oid;
    const def = objectDefs[oid];
    const actions = (OBJECT_ACTIONS[oid] || (() => []))();
    interactPanel.classList.remove("hidden");
    interactPanel.innerHTML = `<h4>${def.name}</h4>`;
    actions.forEach((a) => {
      const b = document.createElement("button");
      b.textContent = a.label;
      b.onclick = async () => {
        if (a.special === "desk") {
          openDesk();
          return;
        }
        if (a.special === "memowall") {
          openMemoWall();
          return;
        }
        const result = await callInteract(oid, a.action, a.payload);
        if (result && result.text) appendLog(result.text);
      };
      interactPanel.appendChild(b);
    });

    // 패널을 오브젝트 근처 화면 좌표(아이소메트릭 투영 기준)에 띄운다.
    const anchor = objectAnchorTile(oid);
    const sx = isoX(anchor.ax, (anchor.minY + anchor.maxY + 1) / 2);
    const sy = isoY(anchor.ax, (anchor.minY + anchor.maxY + 1) / 2);
    const screenX = Math.min(Math.max(sx - 90, 6), canvas.width - 220);
    const screenY = Math.max(sy - 70, 6);
    interactPanel.style.left = screenX + "px";
    interactPanel.style.top = screenY + "px";
  }

  // ------------------------------------------------------------------ 렌더링 (아이소메트릭)
  const FLOOR_COLOR = "#caa36c";
  const FLOOR_COLOR_ALT = "#c39a60";
  const WALL_COLOR_N = "#8a715c";
  const WALL_COLOR_W = "#7a6250";
  const WALL_TRIM = "#5c4a3a";

  function diamondPath(tx, ty, w, h) {
    // 칸 (tx,ty) ~ (tx+w, ty+h) 범위를 덮는 다이아몬드(마름모) 경로.
    ctx.beginPath();
    ctx.moveTo(isoX(tx, ty), isoY(tx, ty));
    ctx.lineTo(isoX(tx + w, ty), isoY(tx + w, ty));
    ctx.lineTo(isoX(tx + w, ty + h), isoY(tx + w, ty + h));
    ctx.lineTo(isoX(tx, ty + h), isoY(tx, ty + h));
    ctx.closePath();
  }

  function drawFloorAndWalls() {
    // 북쪽 벽 (ty=0 가장자리, x축 방향) - 전부 평평한 평행사변형.
    ctx.fillStyle = WALL_COLOR_N;
    ctx.beginPath();
    ctx.moveTo(isoX(0, 0), isoY(0, 0) - WALL_H);
    ctx.lineTo(isoX(ROOM_COLS, 0), isoY(ROOM_COLS, 0) - WALL_H);
    ctx.lineTo(isoX(ROOM_COLS, 0), isoY(ROOM_COLS, 0));
    ctx.lineTo(isoX(0, 0), isoY(0, 0));
    ctx.closePath();
    ctx.fill();

    // 서쪽 벽 (tx=0 가장자리, y축 방향).
    ctx.fillStyle = WALL_COLOR_W;
    ctx.beginPath();
    ctx.moveTo(isoX(0, 0), isoY(0, 0) - WALL_H);
    ctx.lineTo(isoX(0, ROOM_ROWS), isoY(0, ROOM_ROWS) - WALL_H);
    ctx.lineTo(isoX(0, ROOM_ROWS), isoY(0, ROOM_ROWS));
    ctx.lineTo(isoX(0, 0), isoY(0, 0));
    ctx.closePath();
    ctx.fill();

    // 바닥을 가로지르는 바닥선(스커팅) - 벽과 바닥의 경계를 또렷하게.
    ctx.strokeStyle = WALL_TRIM;
    ctx.lineWidth = 2;
    ctx.beginPath();
    ctx.moveTo(isoX(0, 0), isoY(0, 0));
    ctx.lineTo(isoX(ROOM_COLS, 0), isoY(ROOM_COLS, 0));
    ctx.moveTo(isoX(0, 0), isoY(0, 0));
    ctx.lineTo(isoX(0, ROOM_ROWS), isoY(0, ROOM_ROWS));
    ctx.stroke();

    // 창문 (북쪽 벽에 뚫린 창, 타일 좌표 기준으로 위치 계산 - window 오브젝트의 x범위를 그대로 씀).
    const winTiles = objectTiles.window;
    if (winTiles) {
      const minX = Math.min(...winTiles.map((t) => t[0]));
      const maxX = Math.max(...winTiles.map((t) => t[0])) + 1;
      const topY = -WALL_H * 0.82, botY = -WALL_H * 0.18;
      ctx.fillStyle = "#4a3420";
      ctx.beginPath();
      ctx.moveTo(isoX(minX, 0) - 4, isoY(minX, 0) + topY - 4);
      ctx.lineTo(isoX(maxX, 0) + 4, isoY(maxX, 0) + topY - 4);
      ctx.lineTo(isoX(maxX, 0) + 4, isoY(maxX, 0) + botY + 4);
      ctx.lineTo(isoX(minX, 0) - 4, isoY(minX, 0) + botY + 4);
      ctx.closePath();
      ctx.fill();
      // 하늘/바다 느낌의 유리.
      const grad = ctx.createLinearGradient(0, isoY(minX, 0) + topY, 0, isoY(minX, 0) + botY);
      grad.addColorStop(0, "#9fc7e0");
      grad.addColorStop(1, "#4f7fa0");
      ctx.fillStyle = grad;
      ctx.beginPath();
      ctx.moveTo(isoX(minX, 0), isoY(minX, 0) + topY);
      ctx.lineTo(isoX(maxX, 0), isoY(maxX, 0) + topY);
      ctx.lineTo(isoX(maxX, 0), isoY(maxX, 0) + botY);
      ctx.lineTo(isoX(minX, 0), isoY(minX, 0) + botY);
      ctx.closePath();
      ctx.fill();
      // 창틀 중앙 분할선.
      const midX = (minX + maxX) / 2;
      ctx.strokeStyle = "#4a3420";
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.moveTo(isoX(midX, 0), isoY(midX, 0) + topY);
      ctx.lineTo(isoX(midX, 0), isoY(midX, 0) + botY);
      ctx.stroke();
    }

    // 바닥 (다이아몬드 타일 단위, 체크무늬로 은은한 질감).
    for (let y = 0; y < ROOM_ROWS; y++) {
      for (let x = 0; x < ROOM_COLS; x++) {
        if (mapRows[y][x] !== ".") continue;
        ctx.fillStyle = (x + y) % 2 === 0 ? FLOOR_COLOR : FLOOR_COLOR_ALT;
        diamondPath(x, y, 1, 1);
        ctx.fill();
        ctx.strokeStyle = "rgba(0,0,0,0.05)";
        ctx.lineWidth = 1;
        ctx.stroke();
      }
    }

    // 중앙 러그 (진짜 픽셀아트 스프라이트가 로드됐으면 그걸, 아니면 단색).
    const rugTx = 4, rugTy = 5, rugW = 6, rugH = 4;
    if (SPR.rug.loaded) {
      drawGroundSprite(SPR.rug, rugTx + rugW / 2, rugTy + rugH, 3.0);
    } else {
      ctx.fillStyle = "rgba(178,86,63,0.55)";
      diamondPath(rugTx, rugTy, rugW, rugH);
      ctx.fill();
    }
  }

  // 스프라이트를 "바닥에 놓인 것"처럼 그린다 - 기준 타일점(ax,ay)의 화면좌표에 스프라이트 하단-중앙이 오도록.
  function drawGroundSprite(img, ax, ay, scale) {
    if (!img.loaded) return;
    const sx = isoX(ax, ay), sy = isoY(ax, ay);
    const w = img.width * scale, h = img.height * scale;
    ctx.drawImage(img, sx - w / 2, sy - h + TH * 0.3, w, h);
  }

  function drawLabel(text, ax, ay) {
    const sx = isoX(ax, ay), sy = isoY(ax, ay);
    ctx.fillStyle = "#f3e6cf";
    ctx.font = "bold 11px sans-serif";
    ctx.textAlign = "center";
    ctx.shadowColor = "rgba(0,0,0,0.6)";
    ctx.shadowBlur = 3;
    ctx.fillText(text, sx, sy - 6);
    ctx.shadowBlur = 0;
  }

  // 작은 아이콘류(실제 스프라이트가 없는 보조 오브젝트)는 간단한 도형으로 그린다.
  function drawIconProp(oid, ax, ay) {
    const sx = isoX(ax, ay);
    const sy = isoY(ax, ay) - TH * 0.4;
    ctx.save();
    ctx.translate(sx, sy);
    if (oid === "radio") {
      ctx.fillStyle = "#6b4a28";
      ctx.fillRect(-14, -10, 28, 16);
      ctx.strokeStyle = "#2e2016";
      ctx.lineWidth = 1;
      ctx.strokeRect(-14, -10, 28, 16);
      ctx.fillStyle = "#c79a3a";
      ctx.beginPath();
      ctx.arc(-6, -2, 3, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = "#2e2016";
      ctx.beginPath();
      ctx.moveTo(8, -10);
      ctx.lineTo(12, -22);
      ctx.stroke();
    } else if (oid === "memowall") {
      ctx.fillStyle = "#2f3a2a";
      ctx.fillRect(-16, -22, 32, 24);
      ctx.strokeStyle = "#1a2015";
      ctx.strokeRect(-16, -22, 32, 24);
      ctx.fillStyle = "#e8d9a0";
      ctx.fillRect(-11, -17, 7, 7);
      ctx.fillStyle = "#d9a0a0";
      ctx.fillRect(2, -13, 7, 7);
    } else if (oid === "mail") {
      ctx.fillStyle = "#8a5a3a";
      ctx.fillRect(-12, -8, 24, 10);
      ctx.strokeStyle = "#4a3420";
      ctx.strokeRect(-12, -8, 24, 10);
      ctx.fillStyle = "#e8e0c8";
      ctx.fillRect(-8, -14, 14, 8);
      ctx.strokeStyle = "#8a7a50";
      ctx.strokeRect(-8, -14, 14, 8);
    } else if (oid === "door") {
      ctx.fillStyle = "#6b4a2c";
      ctx.fillRect(-13, -46, 26, 46);
      ctx.strokeStyle = "#3a2a18";
      ctx.lineWidth = 2;
      ctx.strokeRect(-13, -46, 26, 46);
      ctx.fillStyle = "#d9b86a";
      ctx.beginPath();
      ctx.arc(7, -22, 2.4, 0, Math.PI * 2);
      ctx.fill();
    } else if (oid === "computer") {
      // desk_plain 위에 모니터를 얹는다 (drawObjects 에서 desk_plain 그린 뒤 호출됨).
      ctx.fillStyle = "#1c1c1c";
      ctx.fillRect(-11, -34, 22, 16);
      ctx.strokeStyle = "#444";
      ctx.strokeRect(-11, -34, 22, 16);
      ctx.fillStyle = "#6fb8d6";
      ctx.fillRect(-8, -31, 16, 10);
      ctx.fillStyle = "#1c1c1c";
      ctx.fillRect(-4, -18, 8, 4);
    }
    ctx.restore();
  }

  const OBJECT_COLOR_FALLBACK = {
    window: "#7ea8c9", boxes: "#c2a066", desk: "#7a5230", computer: "#355142",
    bookshelf: "#5a4024", mirror: "#c7d6e0", bed: "#3f6b4a", radio: "#c79a3a",
    memowall: "#2f3a2a", mail: "#8a5a3a", door: "#4a3420",
  };

  function drawObjectFallback(oid, anchor) {
    // 스프라이트가 아직 로드 전이거나 없는 경우의 안전망 (단색 블록).
    const color = OBJECT_COLOR_FALLBACK[oid] || "#888";
    ctx.fillStyle = color;
    diamondPath(anchor.minX, anchor.minY, anchor.maxX - anchor.minX + 1, anchor.maxY - anchor.minY + 1);
    ctx.fill();
    ctx.strokeStyle = "rgba(0,0,0,0.35)";
    ctx.lineWidth = 2;
    ctx.stroke();
  }

  function drawObjects() {
    // 바닥에 "서 있는" 모든 것(오브젝트 + 플레이어)을 깊이(타일 좌표 합)순으로 정렬해 그린다.
    const entities = [];
    for (const oid of Object.keys(objectTiles)) {
      const anchor = objectAnchorTile(oid);
      entities.push({ type: "object", oid, anchor, depth: anchor.ax + anchor.ay });
    }
    entities.push({ type: "player", depth: player.x + player.y + 0.4 });
    entities.sort((a, b) => a.depth - b.depth);

    entities.forEach((e) => {
      if (e.type === "player") {
        drawPlayer();
        return;
      }
      const { oid, anchor } = e;
      const midY = (anchor.minY + anchor.maxY + 1) / 2;
      if (oid === "boxes") {
        // 상자는 칸마다 하나씩 쌓인 것처럼 반복해서 그린다.
        objectTiles.boxes.forEach(([x, y]) => drawGroundSprite(SPR.box, x + 0.5, y + 1, 1.6));
        if (!SPR.box.loaded) drawObjectFallback(oid, anchor);
      } else if (oid === "bed") {
        if (SPR.bed.loaded) drawGroundSprite(SPR.bed, anchor.ax, anchor.ay, 1.9);
        else drawObjectFallback(oid, anchor);
      } else if (oid === "bookshelf") {
        if (SPR.bookshelf.loaded) drawGroundSprite(SPR.bookshelf, anchor.ax, anchor.ay, 2.0);
        else drawObjectFallback(oid, anchor);
      } else if (oid === "desk") {
        if (SPR.desk_full.loaded) drawGroundSprite(SPR.desk_full, anchor.ax, anchor.ay, 1.75);
        else drawObjectFallback(oid, anchor);
      } else if (oid === "computer") {
        if (SPR.desk_plain.loaded) {
          drawGroundSprite(SPR.desk_plain, anchor.ax, anchor.ay, 1.75);
          drawIconProp("computer", anchor.ax, midY);
        } else drawObjectFallback(oid, anchor);
      } else if (oid === "mirror") {
        if (SPR.mirror.loaded) drawGroundSprite(SPR.mirror, anchor.ax, anchor.ay, 1.9);
        else drawObjectFallback(oid, anchor);
      } else if (["radio", "memowall", "mail", "door"].includes(oid)) {
        drawIconProp(oid, anchor.ax, midY);
      } else if (oid === "window") {
        // 창문은 drawFloorAndWalls 에서 벽에 이미 그렸다 - 여기선 라벨만.
      } else {
        drawObjectFallback(oid, anchor);
      }

      if (oid !== "window") {
        drawLabel(objectDefs[oid].name, anchor.ax, Math.min(anchor.minY, midY));
      } else {
        const wt = objectTiles.window;
        const wx = (Math.min(...wt.map((t) => t[0])) + Math.max(...wt.map((t) => t[0])) + 1) / 2;
        drawLabel(objectDefs[oid].name, wx, 0.15);
      }
    });
  }

  function drawPlayer() {
    const sx = isoX(player.x, player.y);
    const sy = isoY(player.x, player.y);
    // 그림자.
    ctx.fillStyle = "rgba(0,0,0,0.3)";
    ctx.beginPath();
    ctx.ellipse(sx, sy, 12, 5, 0, 0, Math.PI * 2);
    ctx.fill();
    // 2등신 캐릭터: 몸(사각 느낌의 타원) + 머리(큰 원).
    ctx.fillStyle = "#3a2f22";
    ctx.beginPath();
    ctx.ellipse(sx, sy - 14, 10, 12, 0, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = "#e8c79a";
    ctx.beginPath();
    ctx.arc(sx, sy - 30, 12, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillStyle = "#241a0d";
    const eyeOffset = facing === "left" ? -3 : facing === "right" ? 3 : 0;
    ctx.beginPath();
    ctx.arc(sx - 4 + eyeOffset, sy - 31, 1.5, 0, Math.PI * 2);
    ctx.arc(sx + 4 + eyeOffset, sy - 31, 1.5, 0, Math.PI * 2);
    ctx.fill();
  }

  // ------------------------------------------------------------------ 메인 루프
  let last = performance.now();
  function loop(now) {
    const dt = Math.min(0.05, (now - last) / 1000);
    last = now;

    let dx = 0, dy = 0;
    if (keys["ArrowUp"] || keys["w"]) dy -= 1;
    if (keys["ArrowDown"] || keys["s"]) dy += 1;
    if (keys["ArrowLeft"] || keys["a"]) dx -= 1;
    if (keys["ArrowRight"] || keys["d"]) dx += 1;
    if (dx || dy) {
      const len = Math.hypot(dx, dy);
      dx /= len;
      dy /= len;
      const nx = player.x + dx * SPEED * dt;
      const ny = player.y + dy * SPEED * dt;
      if (canStandAt(nx, player.y)) player.x = nx;
      if (canStandAt(player.x, ny)) player.y = ny;
      facing = Math.abs(dx) > Math.abs(dy) ? (dx > 0 ? "right" : "left") : dy > 0 ? "down" : "up";
    }

    ctx.clearRect(0, 0, canvas.width, canvas.height);
    drawFloorAndWalls();
    drawObjects();
    updateInteractPanel();

    requestAnimationFrame(loop);
  }

  refreshChrome();
  requestAnimationFrame(loop);
})();
