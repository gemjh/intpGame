/* ===== INTP 키우기 — 캐릭터 이동 (1차 테스트: 이동 + 가구 충돌) =====
 * editor.js 뒤에 실행된다. 가구 위치는 window.IntpLayout(=확정 배치, fixed-layout.js)을
 * 그대로 읽어서 쓸 뿐 여기서 가구 좌표/크기를 바꾸지 않는다.
 *
 * 이번 단계에서 하는 일: ① 캐릭터를 방에 배치 ② 탭/클릭한 지점으로 자연스럽게 이동
 * ③ 가구에 막혀서 통과하지 못함. 바닥 모양(걷는 범위)과 가구 발판은 모두 1차 근사치이고,
 * 이후 "이동 가능 영역 설정"·"가구 충돌" 단계에서 다듬을 것을 전제로 한다.
 */
(() => {
  'use strict';
  const ROOM_W = 1200, ROOM_H = 800;
  const $ = id => document.getElementById(id);
  const stage = $('stage');
  const modal = $('modal');
  if (!stage) return;

  /* ---------- 바닥(이동 가능 영역) ----------
   * room.jpg(1200x800)를 눈으로 좌표를 찍어 추적한 다각형. 검은 레터박스 영역은 제외했다.
   * 문 앞 매트가 있는 단차 공간까지 포함한 대략치 — 정밀한 자동 추출은 다음 단계 과제. */
  const FLOOR_POLY = [
    [90, 505], [648, 325], [795, 415], [795, 598], [900, 618],
    [1085, 578], [1085, 783], [905, 783], [735, 705], [650, 668], [465, 745]
  ];

  // 바닥에 서 있는 가구만 충돌시킨다. 창문·칠판은 벽에 붙어 있어 바닥 동선과 무관하므로 제외.
  // 거울은 처음엔 벽걸이로 추정해 뺐었는데, 실제 이미지(mirror-new.png)를 보니 받침대가 있는
  // 전신 스탠드 거울이라 바닥을 차지한다 — 충돌 대상에 포함.
  // (라디오는 책상/책장 위에 놓인 소품으로 보고 제외 — 추정이 틀렸다면 쉽게 추가할 수 있다.)
  const COLLIDABLE = new Set(['bed', 'desk', 'boxes', 'drawer', 'shelf', 'mirror']);
  const PLAYER_R = 16;     // 캐릭터 충돌 반지름 (room-px)
  const SPEED = 240;       // 이동 속도 (room-px / 초)
  // 가구 이미지의 bounds(사각형 전체)가 아니라 바닥에 닿는 아랫부분만 발판으로 쓴다 (1차 근사치)
  const FOOT_TOP = 0.6, FOOT_BOTTOM = 0.96, FOOT_INSET = 0.08;

  function pointInPoly(x, y, poly) {
    let inside = false;
    for (let i = 0, j = poly.length - 1; i < poly.length; j = i++) {
      const xi = poly[i][0], yi = poly[i][1], xj = poly[j][0], yj = poly[j][1];
      if ((yi > y) !== (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside;
    }
    return inside;
  }

  function circleHitsRect(cx, cy, r, rect) {
    const nx = Math.max(rect.left, Math.min(cx, rect.right));
    const ny = Math.max(rect.top, Math.min(cy, rect.bottom));
    const dx = cx - nx, dy = cy - ny;
    return dx * dx + dy * dy < r * r;
  }

  function footprints() {
    if (!window.IntpLayout) return [];
    const data = window.IntpLayout.get();   // 편집 중엔 null — 그 사이엔 호출 쪽에서 이동을 멈춘다
    if (!data) return [];
    return data.furniture
      .filter(f => f.visible && COLLIDABLE.has(f.id))
      .map(f => {
        const b = f.bounds, w = b.right - b.left, h = b.bottom - b.top;
        return {
          id: f.id,
          left: b.left + w * FOOT_INSET, right: b.right - w * FOOT_INSET,
          top: b.top + h * FOOT_TOP, bottom: b.top + h * FOOT_BOTTOM
        };
      });
  }

  function canStand(x, y, rects) {
    if (!pointInPoly(x, y, FLOOR_POLY)) return false;
    for (let i = 0; i < rects.length; i++) if (circleHitsRect(x, y, PLAYER_R, rects[i])) return false;
    return true;
  }

  // 편집 모드에서 가구를 캐릭터가 서 있는 자리로 옮기고 "완료"를 누르면, 캐릭터 입장에서는
  // 아무 데도 못 움직였는데 갑자기 가구 안에 갇히게 된다(이동은 "다음 칸이 비어 있는지"만
  // 보기 때문에 지금 서 있는 칸 자체가 막혀버리면 빠져나갈 다음 칸도 똑같이 막힌 걸로 보임).
  // 그래서 매 프레임 지금 위치가 여전히 유효한지 확인하고, 아니라면 가장 가까운 빈 자리로
  // 즉시 보정한다 — 가구 편집 직후뿐 아니라 비슷한 상황이 또 생겨도 같은 방식으로 복구된다.
  function findNearestFree(x, y, rects) {
    if (canStand(x, y, rects)) return { x, y };
    for (let r = 6; r <= 500; r += 6) {
      const steps = Math.max(10, Math.round(r / 4));
      for (let i = 0; i < steps; i++) {
        const a = (i / steps) * Math.PI * 2;
        const nx = x + Math.cos(a) * r, ny = y + Math.sin(a) * r;
        if (canStand(nx, ny, rects)) return { x: nx, y: ny };
      }
    }
    return { x, y };   // 정말 빠져나갈 곳이 없으면(이론상 거의 없음) 그대로 둔다
  }

  /* ---------- 길찾기 ----------
   * 직선 이동만으로는 가구(특히 이삿짐 상자)에 막혀 창문 앞까지 못 가는 문제가 있어서,
   * 직선이 막혀 있으면 격자 BFS로 우회 경로를 찾는다. 목표 지점 자체가 바닥 밖(벽에 걸린 창문 등)
   * 이면 도달 가능한 칸 중 목표에 가장 가까운 칸까지 간다. */
  const CELL = 10;

  function lineClear(ax, ay, bx, by, rects) {
    const n = Math.max(1, Math.ceil(Math.hypot(bx - ax, by - ay) / 4));
    for (let i = 1; i <= n; i++) {
      if (!canStand(ax + (bx - ax) * i / n, ay + (by - ay) * i / n, rects)) return false;
    }
    return true;
  }

  function findPath(sx, sy, tx, ty, rects) {
    if (canStand(tx, ty, rects) && lineClear(sx, sy, tx, ty, rects)) return [{ x: tx, y: ty }];
    const cols = Math.ceil(ROOM_W / CELL), rows = Math.ceil(ROOM_H / CELL);
    const key = (c, r) => r * cols + c;
    const center = (c, r) => ({ x: c * CELL + CELL / 2, y: r * CELL + CELL / 2 });
    const sc = Math.floor(sx / CELL), sr = Math.floor(sy / CELL);
    const prev = new Map([[key(sc, sr), -1]]);
    const queue = [[sc, sr]];
    let best = null, bestD = Infinity;
    for (let qi = 0; qi < queue.length; qi++) {
      const [c, r] = queue[qi];
      const p = center(c, r);
      const d = Math.hypot(p.x - tx, p.y - ty);
      if (d < bestD) { bestD = d; best = [c, r]; }
      if (d < CELL) break;
      for (const [dc, dr] of [[1, 0], [-1, 0], [0, 1], [0, -1], [1, 1], [1, -1], [-1, 1], [-1, -1]]) {
        const nc = c + dc, nr = r + dr, k = key(nc, nr);
        if (nc < 0 || nr < 0 || nc >= cols || nr >= rows || prev.has(k)) continue;
        const np = center(nc, nr);
        if (!canStand(np.x, np.y, rects) || !lineClear(p.x, p.y, np.x, np.y, rects)) continue;
        prev.set(k, key(c, r));
        queue.push([nc, nr]);
      }
    }
    if (!best) return [];
    const cells = [];
    for (let k = key(best[0], best[1]); k !== -1; k = prev.get(k)) cells.push(center(k % cols, Math.floor(k / cols)));
    cells.reverse();
    if (canStand(tx, ty, rects)) cells.push({ x: tx, y: ty });
    // 직선으로 갈 수 있는 중간 지점은 건너뛴다 (지그재그 방지)
    const path = [];
    let from = { x: sx, y: sy };
    for (let i = 0; i < cells.length; i++) {
      if (i === cells.length - 1 || !lineClear(from.x, from.y, cells[i + 1].x, cells[i + 1].y, rects)) {
        path.push(cells[i]);
        from = cells[i];
      }
    }
    return path;
  }

  /* ---------- 캐릭터 DOM / 스프라이트 ---------- */
  // editor.js가 만든 world 레이어(팬/줌 래퍼) 안에 넣어야 같은 좌표계를 공유한다.
  const world = stage.querySelector('.world') || stage;
  const player = document.createElement('div');
  player.className = 'player';
  player.innerHTML = '<div class="player-shadow"></div><img class="player-sprite" alt="">';
  world.appendChild(player);
  const sprite = player.querySelector('.player-sprite');
  const SPRITES = window.INTP_CHARACTER_SPRITES || null;   // 업로드한 참고 시트에서 추출한 스프라이트

  // 시작 위치: 바닥이면서 어떤 가구 발판과도 겹치지 않는 지점(미리 확인한 좌표)
  const pos = { x: 600, y: 650 };
  let target = null;
  let path = [];             // 남은 경유 지점 (target 다음에 갈 곳들)
  let facing = 'front';      // front/back/left/right 중 마지막으로 바라본 방향
  let walkFrame = 0;         // 0~3, 걷기 애니메이션 프레임
  let frameTimer = 0;
  const FRAME_TIME = 0.12;   // 걷기 프레임 전환 주기(초)
  let lastSrc = '';

  function setSprite(src) {
    if (src && src !== lastSrc) { sprite.src = src; lastSrc = src; }
  }

  function updateSprite(moving, dt) {
    if (!SPRITES) return;
    if (moving) {
      frameTimer += dt;
      if (frameTimer >= FRAME_TIME) { frameTimer = 0; walkFrame = (walkFrame + 1) % 4; }
      setSprite(SPRITES.walk[facing][walkFrame]);
    } else {
      frameTimer = 0; walkFrame = 0;
      setSprite(SPRITES.idle[facing]);
    }
  }

  function isEditing() { return !!(window.IntpLayout && window.IntpLayout.isEditing()); }

  function toRoom(cx, cy) {
    const r = world.getBoundingClientRect();
    return { x: (cx - r.left) / r.width * ROOM_W, y: (cy - r.top) / r.height * ROOM_H };
  }

  function render() {
    player.style.left = pos.x / ROOM_W * 100 + '%';
    player.style.top = pos.y / ROOM_H * 100 + '%';
    // 가구 z(1~9)와 대략 겹치도록 y좌표로 앞뒤 순서를 근사한다 (안쪽에 있으면 가구 뒤, 앞쪽이면 가구 앞)
    player.style.zIndex = String(1 + Math.round(pos.y / ROOM_H * 12));
  }

  stage.addEventListener('pointerdown', e => {
    if (isEditing() || e.button > 0) return;
    if (e.target.closest && e.target.closest('.furniture')) return;   // 가구는 기존 관찰 모달 우선
    if (modal && !modal.classList.contains('hidden')) return;          // 모달이 열려 있으면 이동 무시
    const r = toRoom(e.clientX, e.clientY);
    if (!pointInPoly(r.x, r.y, FLOOR_POLY)) return;   // 바닥 밖 탭은 무시 (보정은 다음 단계 과제)
    path = findPath(pos.x, pos.y, r.x, r.y, footprints());
    target = path.shift() || null;
  }, { passive: true });

  let last = performance.now();
  function tick(now) {
    const dt = Math.min(0.05, Math.max(0, (now - last) / 1000));
    last = now;
    let moved = false;
    if (isEditing()) {
      player.classList.add('player-hidden');
      target = null;
      path = [];
    } else {
      player.classList.remove('player-hidden');
      const rects = footprints();
      // 편집 중 가구가 캐릭터 위치로 옮겨와 지금 서 있는 자리가 막혀버렸다면 즉시 빈 자리로 보정
      if (!canStand(pos.x, pos.y, rects)) {
        const free = findNearestFree(pos.x, pos.y, rects);
        pos.x = free.x; pos.y = free.y;
        target = null;
        path = [];
      }
      if (target) {
        const dx = target.x - pos.x, dy = target.y - pos.y;
        const dist = Math.hypot(dx, dy);
        if (dist < 2) {
          target = path.length ? path.shift() : null;
        } else {
          const step = Math.min(dist, SPEED * dt);
          const ux = dx / dist, uy = dy / dist;
          // 바라보는 방향: 더 크게 움직이는 축을 기준으로 4방향 중 하나를 고른다
          facing = Math.abs(ux) > Math.abs(uy) ? (ux > 0 ? 'right' : 'left') : (uy > 0 ? 'front' : 'back');
          // 축을 분리해서 이동 → 가구 모서리를 따라 자연스럽게 미끄러지듯 멈춘다
          const nx = pos.x + ux * step;
          if (canStand(nx, pos.y, rects)) { pos.x = nx; moved = true; }
          const ny = pos.y + uy * step;
          if (canStand(pos.x, ny, rects)) { pos.y = ny; moved = true; }
          if (!moved) { target = null; path = []; }   // 완전히 막혔으면 포기
        }
      }
    }
    updateSprite(moved, dt);
    render();
    requestAnimationFrame(tick);
  }
  updateSprite(false, 0);
  render();
  requestAnimationFrame(tick);

  // 원본 안내 문구("캐릭터 이동 미구현")는 이 단계부터 더 이상 사실이 아니므로 갱신한다.
  // (source-261008.html 자체는 건드리지 않고, 실행 중에 텍스트만 바꾼다)
  const notice = document.querySelector('.notice');
  if (notice) notice.textContent = '바닥을 탭/클릭하면 이동 · 가구를 누르면 가까이 걸어가서 상호작용';

  // game-interact.js(가구 접근/상호작용)가 이동을 걸고 상태를 읽을 수 있도록 노출
  window.IntpPlayer = {
    getPos: () => ({ x: pos.x, y: pos.y }),
    setPos: (x, y) => { pos.x = x; pos.y = y; target = null; path = []; render(); },
    walkTo: (x, y) => {
      path = findPath(pos.x, pos.y, x, y, footprints());
      target = path.shift() || null;
    },
    isMoving: () => !!target
  };
})();
