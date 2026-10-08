/* ===== INTP 키우기 — 가구 배치 편집기 =====
 * 원본 게임 스크립트 뒤에 실행된다. 원본 DOM/이벤트는 그대로 두고 위에 얹는 구조.
 * 좌표계: 방 배경 원본 이미지(1200×800) 픽셀. 가구 x,y는 이미지 중심점.
 */
(() => {
  'use strict';
  const ROOM_W = 1200, ROOM_H = 800;
  const STORE_KEY = 'intp.roomLayout.v1';
  const FORMAT = 'intp-room-layout', VERSION = 1;
  const MIN_SIZE = 10, MIN_SCALE = 20, MAX_SCALE = 300, MAX_ZOOM = 5, UNDO_LIMIT = 100;
  const $ = id => document.getElementById(id);
  const r1 = v => Math.round(v * 10) / 10;
  const clamp = (v, a, b) => Math.min(b, Math.max(a, v));

  const stage = $('stage');
  const modal = $('modal');

  // 확대/이동을 위해 방 배경과 가구를 하나의 world 레이어로 감싼다 (요소 자체는 그대로 이동만)
  const world = document.createElement('div');
  world.className = 'world';
  while (stage.firstChild) world.appendChild(stage.firstChild);
  stage.appendChild(world);
  const selLabel = document.createElement('div');
  selLabel.className = 'sel-label';
  selLabel.hidden = true;
  world.appendChild(selLabel);

  const items = [...world.querySelectorAll('.furniture')].map((el, i) => ({
    id: el.dataset.key,
    name: el.getAttribute('aria-label') || el.dataset.key,
    el, img: el.querySelector('img'), dom: i,
    css: {
      left: parseFloat(el.style.left), top: parseFloat(el.style.top),
      width: parseFloat(el.style.width), z: parseInt(el.style.zIndex, 10) || 0
    }
  }));
  const byId = Object.fromEntries(items.map(it => [it.id, it]));

  let L = {};      // 현재 배치: id -> {x,y,w,h,z,visible,lock}
  let DEF = {};    // 원본 HTML 기준 초기 배치
  let sel = null, editing = false, dirty = false, savedAt = null;
  const undoStack = [];
  let mutKey = null;
  const view = { s: 1, tx: 0, ty: 0 };

  /* ---------- 초기 배치 / 알파 마스크 ---------- */
  function buildDefaults() {
    const out = {};
    [...items].sort((a, b) => a.css.z - b.css.z || a.dom - b.dom).forEach((it, rank) => {
      const w = it.css.width / 100 * ROOM_W;
      out[it.id] = {
        x: r1(it.css.left / 100 * ROOM_W), y: r1(it.css.top / 100 * ROOM_H),
        w: r1(w), h: r1(w * it.nh / it.nw), z: rank + 1, visible: true, lock: true, rot: 0
      };
    });
    return out;
  }

  // 확정 배치(window.INTP_FIXED_LAYOUT, fixed-layout.js)가 있으면 그것을 기준 배치로 쓴다.
  // 원본 HTML의 %값(buildDefaults)은 가구 위치를 다듬기 전의 임시 배치였을 뿐이라
  // 더 이상 기준으로 삼지 않는다. deserialize()는 아래에서 선언되지만 함수 선언이라 호이스팅된다.
  function buildFixedDefaults() {
    if (!window.INTP_FIXED_LAYOUT) return null;
    try { return deserialize(window.INTP_FIXED_LAYOUT).layout; }
    catch (err) {
      console.warn('[배치 편집기] 확정 배치 JSON을 읽지 못해 원본 배치를 사용합니다:', err.message);
      return null;
    }
  }

  // 투명 영역을 제외한 실제 그림 위치로 선택 판정 (얇은 부분도 잡히도록 약간 팽창)
  function buildMask(it) {
    const k = Math.min(1, 160 / Math.max(it.nw, it.nh));
    const mw = Math.max(1, Math.round(it.nw * k)), mh = Math.max(1, Math.round(it.nh * k));
    const c = document.createElement('canvas');
    c.width = mw; c.height = mh;
    const g = c.getContext('2d', { willReadFrequently: true });
    g.drawImage(it.img, 0, 0, mw, mh);
    let a;
    try { a = g.getImageData(0, 0, mw, mh).data; } catch (e) { it.mask = null; return; }
    const m = new Uint8Array(mw * mh), d = new Uint8Array(mw * mh), R = 2;
    for (let i = 0; i < mw * mh; i++) if (a[i * 4 + 3] > 40) m[i] = 1;
    for (let y = 0; y < mh; y++) for (let x = 0; x < mw; x++) {
      if (!m[y * mw + x]) continue;
      for (let yy = Math.max(0, y - R); yy <= Math.min(mh - 1, y + R); yy++)
        for (let xx = Math.max(0, x - R); xx <= Math.min(mw - 1, x + R); xx++) d[yy * mw + xx] = 1;
    }
    it.mask = { w: mw, h: mh, d };
  }

  function inside(it, rx, ry, useMask) {
    const p = L[it.id];
    // 회전된 가구는 점을 반대로 돌려서 원래 이미지 좌표로 판정
    const t = -(p.rot || 0) * Math.PI / 180, dx = rx - p.x, dy = ry - p.y;
    const u = (dx * Math.cos(t) - dy * Math.sin(t)) / p.w + 0.5;
    const v = (dx * Math.sin(t) + dy * Math.cos(t)) / p.h + 0.5;
    if (u < 0 || u >= 1 || v < 0 || v >= 1) return false;
    if (!useMask || !it.mask) return true;
    const m = it.mask;
    return !!m.d[Math.floor(v * m.h) * m.w + Math.floor(u * m.w)];
  }

  function hit(rx, ry) {
    const order = items.filter(it => L[it.id].visible).sort((a, b) => L[b.id].z - L[a.id].z);
    for (const it of order) if (inside(it, rx, ry, true)) return it;
    // 선택된 가구는 투명 영역을 포함한 사각형 전체로 잡을 수 있다
    if (sel && inside(sel, rx, ry, false)) return sel;
    return null;
  }

  /* ---------- 화면 반영 ---------- */
  function applyItem(it) {
    const p = L[it.id], s = it.el.style;
    s.left = p.x / ROOM_W * 100 + '%';
    s.top = p.y / ROOM_H * 100 + '%';
    s.width = p.w / ROOM_W * 100 + '%';
    s.height = p.h / ROOM_H * 100 + '%';
    s.zIndex = p.z;
    s.transform = p.rot ? `translate(-50%,-50%) rotate(${p.rot}deg)` : '';
    it.el.classList.toggle('is-hidden', !p.visible);
    it.el.classList.toggle('is-selected', editing && sel === it);
  }

  function updateLabel() {
    if (!editing || !sel) { selLabel.hidden = true; return; }
    const p = L[sel.id], top = p.y - p.h / 2, below = top < 60;
    selLabel.hidden = false;
    selLabel.textContent = sel.name;
    selLabel.classList.toggle('below', below);
    selLabel.style.left = p.x / ROOM_W * 100 + '%';
    selLabel.style.top = (below ? p.y + p.h / 2 : top) / ROOM_H * 100 + '%';
  }

  function applyAll() { items.forEach(applyItem); updateLabel(); }

  /* ---------- 데이터 ---------- */
  const snapshot = () => JSON.parse(JSON.stringify(L));

  function normalizeZ(map) {
    items.map(it => it.id)
      .sort((a, b) => map[a].z - map[b].z || byId[a].dom - byId[b].dom)
      .forEach((id, i) => { map[id].z = i + 1; });
  }

  function serialize() {
    return {
      format: FORMAT,
      version: VERSION,
      savedAt: new Date().toISOString(),
      coordinateSystem: {
        width: ROOM_W, height: ROOM_H, unit: 'room-px', origin: 'top-left', anchor: 'center',
        note: '방 배경 원본 이미지(1200x800) 픽셀 좌표. x,y는 가구 이미지 중심점. z가 클수록 앞.'
      },
      furniture: items.map(it => {
        const p = L[it.id], t = (p.rot || 0) * Math.PI / 180;
        const hw = (Math.abs(p.w * Math.cos(t)) + Math.abs(p.h * Math.sin(t))) / 2;
        const hh = (Math.abs(p.w * Math.sin(t)) + Math.abs(p.h * Math.cos(t))) / 2;
        return {
          id: it.id, name: it.name,
          x: r1(p.x), y: r1(p.y), width: r1(p.w), height: r1(p.h),
          scale: +(p.w / DEF[it.id].w).toFixed(3),
          rotation: r1(p.rot || 0),
          aspectLocked: p.lock, z: p.z, visible: p.visible,
          bounds: {   // 회전을 반영한 축 정렬 외곽 사각형
            left: r1(p.x - hw), top: r1(p.y - hh),
            right: r1(p.x + hw), bottom: r1(p.y + hh)
          }
        };
      })
    };
  }

  function deserialize(obj) {
    if (!obj || typeof obj !== 'object' || !Array.isArray(obj.furniture))
      throw new Error('가구 배치 JSON 형식이 아닙니다.');
    if (obj.format && obj.format !== FORMAT) throw new Error('다른 형식의 JSON입니다: ' + obj.format);
    const cs = obj.coordinateSystem || {};
    const sx = cs.width > 0 ? ROOM_W / cs.width : 1, sy = cs.height > 0 ? ROOM_H / cs.height : 1;
    const isNum = v => typeof v === 'number' && isFinite(v);
    const out = snapshot(), unknown = [];
    let count = 0;
    for (const f of obj.furniture) {
      if (!f || !byId[f.id]) { unknown.push(f && f.id); continue; }
      if (![f.x, f.y, f.width, f.height].every(isNum))
        throw new Error(`${f.id}: 좌표 또는 크기 값이 올바르지 않습니다.`);
      let w = Math.max(MIN_SIZE, f.width * sx), h = Math.max(MIN_SIZE, f.height * sy);
      const it = byId[f.id], ar = it.nw / it.nh;
      // 이미지가 교체되어 비율이 달라졌으면(비율 유지 상태일 때) 면적은 유지하고 새 비율로 맞춘다
      if (f.aspectLocked !== false && it.nw && Math.abs(w / h - ar) > 0.02 * ar) {
        w = r1(Math.sqrt(w * h * ar));
        h = r1(w / ar);
      }
      out[f.id] = {
        x: f.x * sx, y: f.y * sy, w, h,
        z: isNum(f.z) ? f.z : out[f.id].z,
        visible: f.visible !== false, lock: f.aspectLocked !== false,
        rot: isNum(f.rotation) ? clamp(f.rotation, -180, 180) : 0
      };
      count++;
    }
    if (!count) throw new Error('적용할 가구가 없습니다.');
    normalizeZ(out);
    return { layout: out, count, unknown };
  }

  function storeGet() { try { return localStorage.getItem(STORE_KEY); } catch (e) { return null; } }
  function storeSet(v) { try { localStorage.setItem(STORE_KEY, v); return true; } catch (e) { return false; } }

  /* ---------- 변경 / 실행 취소 ---------- */
  // 같은 key로 이어지는 변경(드래그, 슬라이더)은 실행 취소 1번으로 묶는다
  function mutate(key, fn) {
    if (!key || key !== mutKey) {
      undoStack.push(snapshot());
      if (undoStack.length > UNDO_LIMIT) undoStack.shift();
    }
    mutKey = key;
    fn();
    setDirty(true);
    applyAll();
    syncPanel();
  }
  const endGesture = () => { mutKey = null; };

  function replaceLayout(next, msg) {
    mutate(null, () => { L = next; });
    if (msg) toast(msg);
  }

  function undo() {
    if (!undoStack.length) { toast('되돌릴 작업이 없습니다'); return; }
    L = undoStack.pop();
    mutKey = null;
    setDirty(true);
    applyAll();
    syncPanel();
    toast('실행 취소');
  }

  function setDirty(v) { dirty = v; renderStatus(); }

  /* ---------- 확대 / 이동 ---------- */
  function setView(s, tx, ty) {
    const W = stage.clientWidth, H = stage.clientHeight;
    s = clamp(s, 1, MAX_ZOOM);
    view.s = s;
    view.tx = clamp(tx, W - W * s, 0);
    view.ty = clamp(ty, H - H * s, 0);
    world.style.transform = s === 1 ? '' : `translate(${view.tx}px,${view.ty}px) scale(${s})`;
    world.style.setProperty('--inv', 1 / s);
    const zv = $('zVal');
    if (zv) zv.textContent = Math.round(s * 100) + '%';
  }

  function zoomAt(cx, cy, s) {
    const r = stage.getBoundingClientRect();
    const px = cx - r.left, py = cy - r.top;
    const wx = (px - view.tx) / view.s, wy = (py - view.ty) / view.s;
    s = clamp(s, 1, MAX_ZOOM);
    setView(s, px - wx * s, py - wy * s);
  }

  function zoomCenter(f) {
    const r = stage.getBoundingClientRect();
    if (sel) {   // 선택된 가구를 중심으로 확대
      const p = L[sel.id], w = world.getBoundingClientRect();
      zoomAt(w.left + p.x / ROOM_W * w.width, w.top + p.y / ROOM_H * w.height, view.s * f);
    } else zoomAt(r.left + r.width / 2, r.top + r.height / 2, view.s * f);
  }

  // 화면 좌표 → 방 이미지 좌표. 확대/스크롤/기기 크기와 무관하게 정확하다.
  function toRoom(cx, cy) {
    const r = world.getBoundingClientRect();
    return { x: (cx - r.left) / r.width * ROOM_W, y: (cy - r.top) / r.height * ROOM_H };
  }

  /* ---------- 포인터 (마우스 + 터치 공통) ---------- */
  const ptrs = new Map();
  let gesture = null, dragSeq = 0;

  function startPinch() {
    const [a, b] = [...ptrs.values()];
    gesture = {
      type: 'pinch',
      d0: Math.hypot(a.x - b.x, a.y - b.y) || 1,
      mx: (a.x + b.x) / 2, my: (a.y + b.y) / 2,
      s0: view.s, tx0: view.tx, ty0: view.ty
    };
  }

  function movePinch() {
    const [a, b] = [...ptrs.values()];
    const g = gesture, r = stage.getBoundingClientRect();
    const s = clamp(g.s0 * Math.hypot(a.x - b.x, a.y - b.y) / g.d0, 1, MAX_ZOOM);
    const wx = (g.mx - r.left - g.tx0) / g.s0, wy = (g.my - r.top - g.ty0) / g.s0;
    const mx = (a.x + b.x) / 2 - r.left, my = (a.y + b.y) / 2 - r.top;
    setView(s, mx - wx * s, my - wy * s);
  }

  stage.addEventListener('pointerdown', e => {
    if (!editing || e.button > 0) return;
    e.preventDefault();
    try { stage.setPointerCapture(e.pointerId); } catch (err) { /* 무시 */ }
    ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
    if (ptrs.size === 2) { endGesture(); startPinch(); return; }
    if (ptrs.size > 2) return;
    const r = toRoom(e.clientX, e.clientY), it = hit(r.x, r.y);
    if (it) {
      select(it);
      const p = L[it.id];
      gesture = { type: 'drag', id: e.pointerId, it, sx: e.clientX, sy: e.clientY, r0: r, ox: p.x, oy: p.y, moved: false, key: 'drag:' + (++dragSeq) };
    } else {
      gesture = { type: 'pan', id: e.pointerId, sx: e.clientX, sy: e.clientY, tx0: view.tx, ty0: view.ty, moved: false };
    }
  });

  stage.addEventListener('pointermove', e => {
    if (!editing || !ptrs.has(e.pointerId)) return;
    ptrs.set(e.pointerId, { x: e.clientX, y: e.clientY });
    const g = gesture;
    if (!g) return;
    if (g.type === 'pinch') { if (ptrs.size >= 2) movePinch(); return; }
    if (g.id !== e.pointerId) return;
    if (!g.moved && Math.hypot(e.clientX - g.sx, e.clientY - g.sy) < 4) return;
    g.moved = true;
    if (g.type === 'drag') {
      const r = toRoom(e.clientX, e.clientY);
      mutate(g.key, () => {
        const p = L[g.it.id];
        p.x = clamp(Math.round(g.ox + r.x - g.r0.x), 0, ROOM_W);
        p.y = clamp(Math.round(g.oy + r.y - g.r0.y), 0, ROOM_H);
      });
    } else if (view.s > 1) {
      setView(view.s, g.tx0 + e.clientX - g.sx, g.ty0 + e.clientY - g.sy);
    }
  });

  function pointerEnd(e) {
    if (!ptrs.has(e.pointerId)) return;
    ptrs.delete(e.pointerId);
    const g = gesture;
    if (g && g.type === 'pinch') { if (ptrs.size === 0) gesture = null; return; }
    if (g && g.id === e.pointerId) {
      if (g.type === 'pan' && !g.moved && e.type === 'pointerup') select(null);
      endGesture();
      gesture = null;
    }
  }
  stage.addEventListener('pointerup', pointerEnd);
  stage.addEventListener('pointercancel', pointerEnd);

  stage.addEventListener('wheel', e => {
    if (!editing) return;
    e.preventDefault();
    zoomAt(e.clientX, e.clientY, view.s * Math.exp(-e.deltaY * 0.0015));
  }, { passive: false });

  // 편집 모드에서는 가구 클릭(관찰 모달)을 막는다. 캡처 단계에서 차단 → 원본 핸들러 미실행
  stage.addEventListener('click', e => {
    if (editing) { e.stopPropagation(); e.preventDefault(); }
  }, true);

  window.addEventListener('resize', () => { if (editing) setView(view.s, view.tx, view.ty); });

  /* ---------- 편집 패널 ---------- */
  const panel = document.createElement('section');
  panel.className = 'ed';
  panel.id = 'ed';
  panel.hidden = true;
  panel.innerHTML = `
    <div class="ed-bar">
      <button id="edDone" class="pri">완료</button>
      <button id="edUndo">↶ 취소</button>
      <button id="edSave">저장</button>
      <span class="sp"></span>
      <span class="ed-zoom"><button id="zOut" aria-label="축소">−</button><span id="zVal">100%</span><button id="zIn" aria-label="확대">+</button><button id="zFit">맞춤</button></span>
      <button id="edMore" aria-expanded="false">⋯</button>
    </div>
    <div class="ed-menu" id="edMenu" hidden>
      <button id="edLoad">저장된 배치 불러오기</button>
      <button id="edReset" class="warn">초기 배치로 복원</button>
      <button id="edExport">JSON 내보내기</button>
      <button id="edImport">JSON 가져오기</button>
      <input type="file" id="edFile" accept=".json,application/json" hidden>
    </div>
    <div class="ed-status" id="edStatus"></div>
    <div id="edNone">
      <p class="ed-hint">가구를 터치해서 선택한 뒤 드래그하세요. 두 손가락으로 방을 확대할 수 있어요.</p>
      <div class="ed-chips" id="edChips"></div>
    </div>
    <div id="edSel" hidden>
      <div class="ed-card">
        <div class="ed-head"><strong id="sName"></strong><small id="sLayer"></small><span class="sp"></span>
          <label class="chk"><input type="checkbox" id="sVis">표시</label>
          <button id="sClose" aria-label="선택 해제">✕</button></div>
      </div>
      <div class="ed-card">
        <h4>위치 (방 이미지 1200×800 기준, 중심점)</h4>
        <div class="ed-row"><label class="k" for="sX">X</label>
          <button data-nudge="x,-10">−10</button><button data-nudge="x,-1">−1</button>
          <input type="number" id="sX" inputmode="decimal" step="any">
          <button data-nudge="x,1">+1</button><button data-nudge="x,10">+10</button></div>
        <div class="ed-row"><label class="k" for="sY">Y</label>
          <button data-nudge="y,-10">−10</button><button data-nudge="y,-1">−1</button>
          <input type="number" id="sY" inputmode="decimal" step="any">
          <button data-nudge="y,1">+1</button><button data-nudge="y,10">+10</button></div>
      </div>
      <div class="ed-card">
        <h4>크기 (초기 크기 대비)</h4>
        <div class="ed-row"><input type="range" id="sScale" min="${MIN_SCALE}" max="${MAX_SCALE}" step="1">
          <input type="number" id="sScaleN" class="pct" inputmode="decimal" step="any" min="${MIN_SCALE}" max="${MAX_SCALE}"><span>%</span></div>
        <div class="ed-row"><label class="chk"><input type="checkbox" id="sLock">비율 유지</label></div>
        <div class="ed-row"><label class="k" for="sW">W</label><input type="number" id="sW" inputmode="decimal" step="any">
          <label class="k" for="sH">H</label><input type="number" id="sH" inputmode="decimal" step="any"></div>
      </div>
      <div class="ed-card">
        <h4>각도 (도, 시계 방향 +)</h4>
        <div class="ed-row"><input type="range" id="sRot" min="-45" max="45" step="0.5">
          <input type="number" id="sRotN" class="pct" inputmode="decimal" step="any" min="-180" max="180"><span>°</span></div>
        <div class="ed-row">
          <button data-rot="-5">−5°</button><button data-rot="-1">−1°</button><button data-rot="0">0°</button>
          <button data-rot="1">+1°</button><button data-rot="5">+5°</button></div>
      </div>
      <div class="ed-card">
        <h4>레이어 (위쪽이 앞)</h4>
        <div class="ed-grid4">
          <button data-layer="back">맨 뒤</button><button data-layer="down">뒤로</button>
          <button data-layer="up">앞으로</button><button data-layer="front">맨 앞</button>
        </div>
        <ol class="ed-layers" id="sLayers"></ol>
      </div>
    </div>`;
  stage.insertAdjacentElement('afterend', panel);

  const toastEl = document.createElement('div');
  toastEl.className = 'ed-toast';
  document.body.appendChild(toastEl);
  let toastT = 0;
  function toast(msg) {
    toastEl.textContent = msg;
    toastEl.classList.add('show');
    clearTimeout(toastT);
    toastT = setTimeout(() => toastEl.classList.remove('show'), 1800);
  }

  const fmtTime = iso => {
    const d = new Date(iso);
    if (isNaN(d)) return '';
    const p = n => String(n).padStart(2, '0');
    return `${d.getMonth() + 1}/${d.getDate()} ${p(d.getHours())}:${p(d.getMinutes())}`;
  };

  function renderStatus() {
    const el = $('edStatus');
    if (!el) return;
    el.classList.toggle('dirty', dirty);
    el.textContent = (dirty ? '● 저장하지 않은 변경사항이 있습니다' : '저장된 상태')
      + (savedAt ? ` · 마지막 저장 ${fmtTime(savedAt)}` : ' · 저장 기록 없음');
  }

  const sortedByZDesc = () => [...items].sort((a, b) => L[b.id].z - L[a.id].z);

  function setVal(id, v) {
    const el = $(id);
    if (document.activeElement !== el) el.value = v;
  }

  function syncPanel() {
    if (!editing) return;
    $('edUndo').disabled = !undoStack.length;
    $('edNone').hidden = !!sel;
    $('edSel').hidden = !sel;
    if (!sel) {
      $('edChips').innerHTML = items.map(it =>
        `<button data-pick="${it.id}" class="${L[it.id].visible ? '' : 'off'}">${it.name}</button>`).join('');
      return;
    }
    const p = L[sel.id], d = DEF[sel.id], scale = Math.round(p.w / d.w * 100);
    $('sName').textContent = sel.name;
    $('sLayer').textContent = `레이어 ${p.z} / ${items.length}`;
    $('sVis').checked = p.visible;
    $('sLock').checked = p.lock;
    setVal('sX', r1(p.x)); setVal('sY', r1(p.y));
    setVal('sW', r1(p.w)); setVal('sH', r1(p.h));
    setVal('sScale', clamp(scale, MIN_SCALE, MAX_SCALE));
    setVal('sScaleN', scale);
    setVal('sRot', clamp(p.rot || 0, -45, 45));
    setVal('sRotN', r1(p.rot || 0));
    $('sLayers').innerHTML = sortedByZDesc().map(it =>
      `<li><button data-pick="${it.id}" class="${it === sel ? 'on' : ''} ${L[it.id].visible ? '' : 'off'}">${L[it.id].z}. ${it.name}${L[it.id].visible ? '' : ' (숨김)'}</button></li>`).join('');
  }

  function select(it) {
    if (sel === it) return;
    sel = it;
    applyAll();
    syncPanel();
  }

  /* --- 패널 이벤트 --- */
  panel.addEventListener('click', e => {
    const b = e.target.closest('button');
    if (!b) return;
    if (b.dataset.pick) { select(byId[b.dataset.pick]); return; }
    if (b.dataset.nudge && sel) {
      const [axis, n] = b.dataset.nudge.split(',');
      const id = sel.id;
      mutate(null, () => {
        const p = L[id];
        p[axis] = clamp(Math.round(p[axis] + +n), 0, axis === 'x' ? ROOM_W : ROOM_H);
      });
      return;
    }
    if (b.dataset.rot && sel) {
      const id = sel.id, n = +b.dataset.rot;
      mutate(null, () => { const p = L[id]; p.rot = n === 0 ? 0 : r1(clamp((p.rot || 0) + n, -180, 180)); });
      return;
    }
    if (b.dataset.layer && sel) { moveLayer(sel, b.dataset.layer); return; }
  });

  function moveLayer(it, how) {
    const order = items.map(x => x.id).sort((a, b) => L[a].z - L[b].z); // 뒤 → 앞
    const i = order.indexOf(it.id);
    let j = i;
    if (how === 'back') j = 0;
    else if (how === 'front') j = order.length - 1;
    else if (how === 'down') j = Math.max(0, i - 1);
    else if (how === 'up') j = Math.min(order.length - 1, i + 1);
    if (j === i) { toast(how === 'back' || how === 'down' ? '이미 맨 뒤입니다' : '이미 맨 앞입니다'); return; }
    order.splice(i, 1);
    order.splice(j, 0, it.id);
    mutate(null, () => order.forEach((id, k) => { L[id].z = k + 1; }));
  }

  // 슬라이더는 움직이는 동안 바로 반영, 숫자 칸은 입력을 마쳤을 때(엔터/포커스 이동) 반영
  function bindNum(id, key, apply) {
    const el = $(id), live = el.type === 'range';
    const run = () => {
      const v = parseFloat(el.value);
      if (!sel || !isFinite(v)) return;
      const sid = sel.id;
      mutate(live ? key + ':' + sid : null, () => apply(L[sid], v, sid));
    };
    if (live) el.addEventListener('input', run);
    el.addEventListener('change', () => {
      if (!live) run();
      endGesture();
      if (!live) el.blur();
      syncPanel();
    });
  }

  const setW = (p, w, sid) => {
    const it = byId[sid];
    w = Math.max(MIN_SIZE, w);
    if (p.lock) p.h = r1(w * it.nh / it.nw);
    p.w = w;
  };
  const setScale = (p, pct, sid) => {
    const d = DEF[sid], f = clamp(pct, MIN_SCALE, MAX_SCALE) / 100;
    if (p.lock) { p.w = r1(d.w * f); p.h = r1(d.h * f); }
    else { const k = d.w * f / p.w; p.w = r1(p.w * k); p.h = r1(Math.max(MIN_SIZE, p.h * k)); }
  };

  bindNum('sX', 'x', (p, v) => { p.x = clamp(v, 0, ROOM_W); });
  bindNum('sY', 'y', (p, v) => { p.y = clamp(v, 0, ROOM_H); });
  bindNum('sW', 'w', setW);
  bindNum('sH', 'h', (p, v, sid) => {
    const it = byId[sid];
    v = Math.max(MIN_SIZE, v);
    if (p.lock) p.w = r1(v * it.nw / it.nh);
    p.h = v;
  });
  bindNum('sScale', 'scale', setScale);
  bindNum('sScaleN', 'scale', setScale);
  const setRot = (p, v) => { p.rot = r1(clamp(v, -180, 180)); };
  bindNum('sRot', 'rot', setRot);
  bindNum('sRotN', 'rot', setRot);

  $('sLock').addEventListener('change', e => {
    if (!sel) return;
    const it = sel, on = e.target.checked;
    mutate(null, () => {
      const p = L[it.id];
      p.lock = on;
      if (on) p.h = r1(p.w * it.nh / it.nw);   // 원본 이미지 비율로 되돌림
    });
  });
  $('sVis').addEventListener('change', e => {
    if (!sel) return;
    const id = sel.id, on = e.target.checked;
    mutate(null, () => { L[id].visible = on; });
  });
  $('sClose').addEventListener('click', () => select(null));

  $('edDone').addEventListener('click', () => setEditing(false));
  $('edUndo').addEventListener('click', undo);
  $('edSave').addEventListener('click', save);
  $('zIn').addEventListener('click', () => zoomCenter(1.5));
  $('zOut').addEventListener('click', () => zoomCenter(1 / 1.5));
  $('zFit').addEventListener('click', () => setView(1, 0, 0));
  $('edMore').addEventListener('click', () => {
    const m = $('edMenu');
    m.hidden = !m.hidden;
    $('edMore').setAttribute('aria-expanded', String(!m.hidden));
  });

  function save() {
    const data = serialize();
    if (!storeSet(JSON.stringify(data))) {
      toast('브라우저 저장 실패 — JSON 내보내기를 사용하세요');
      return;
    }
    savedAt = data.savedAt;
    setDirty(false);
    toast('배치를 저장했습니다');
  }

  $('edLoad').addEventListener('click', () => {
    const raw = storeGet();
    if (!raw) { toast('저장된 배치가 없습니다'); return; }
    try {
      const obj = JSON.parse(raw), res = deserialize(obj);
      replaceLayout(res.layout, '저장된 배치를 불러왔습니다');
      savedAt = obj.savedAt || savedAt;
      setDirty(false);
    } catch (err) { toast('불러오기 실패: ' + err.message); }
  });

  $('edReset').addEventListener('click', () => {
    if (!confirm('모든 가구를 초기 배치로 되돌릴까요?\n(실행 취소로 되돌릴 수 있어요)')) return;
    replaceLayout(JSON.parse(JSON.stringify(DEF)), '초기 배치로 복원했습니다');
  });

  $('edExport').addEventListener('click', () => {
    const data = serialize();
    const blob = new Blob([JSON.stringify(data, null, 2)], { type: 'application/json' });
    const d = new Date(), p = n => String(n).padStart(2, '0');
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = `intp-room-layout-${d.getFullYear()}${p(d.getMonth() + 1)}${p(d.getDate())}-${p(d.getHours())}${p(d.getMinutes())}.json`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(a.href), 4000);
    toast('JSON 파일을 내보냈습니다');
  });

  $('edImport').addEventListener('click', () => $('edFile').click());
  $('edFile').addEventListener('change', e => {
    const f = e.target.files && e.target.files[0];
    e.target.value = '';
    if (!f) return;
    const rd = new FileReader();
    rd.onload = () => {
      try {
        const res = deserialize(JSON.parse(rd.result));
        replaceLayout(res.layout, `${res.count}개 가구 배치를 가져왔습니다` +
          (res.unknown.length ? ` (알 수 없는 항목 ${res.unknown.length}개 무시)` : '') + ' · 저장을 눌러야 유지됩니다');
      } catch (err) { toast('가져오기 실패: ' + err.message); }
    };
    rd.onerror = () => toast('파일을 읽을 수 없습니다');
    rd.readAsText(f);
  });

  /* ---------- 키보드 ---------- */
  document.addEventListener('keydown', e => {
    if (!editing) return;
    const tag = (document.activeElement && document.activeElement.tagName) || '';
    if (tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT') return;
    if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'z') { e.preventDefault(); undo(); return; }
    if (e.key === 'Escape') { select(null); return; }
    if (!sel) return;
    if (e.code === 'BracketLeft' || e.code === 'BracketRight') {
      e.preventDefault();
      const id = sel.id, n = (e.code === 'BracketLeft' ? -1 : 1) * (e.shiftKey ? 5 : 1);
      mutate('rotkey:' + id + ':' + e.code, () => { const p = L[id]; p.rot = r1(clamp((p.rot || 0) + n, -180, 180)); });
      return;
    }
    const step = e.shiftKey ? 10 : 1;
    const dir = { ArrowLeft: ['x', -step], ArrowRight: ['x', step], ArrowUp: ['y', -step], ArrowDown: ['y', step] }[e.key];
    if (!dir) return;
    e.preventDefault();
    const id = sel.id;
    mutate('key:' + id + ':' + e.key, () => {
      const p = L[id];
      p[dir[0]] = clamp(Math.round(p[dir[0]] + dir[1]), 0, dir[0] === 'x' ? ROOM_W : ROOM_H);
    });
  });
  document.addEventListener('keyup', e => { if (e.key.startsWith('Arrow') || e.code.startsWith('Bracket')) endGesture(); });

  /* ---------- 모드 전환 ---------- */
  const editBtn = document.createElement('button');
  editBtn.id = 'editBtn';
  editBtn.textContent = '배치 편집';
  editBtn.disabled = true;   // 이미지와 배치 데이터 준비 후 활성화
  const footer = document.querySelector('footer');
  footer.insertBefore(editBtn, $('next'));
  editBtn.addEventListener('click', () => setEditing(true));

  function setEditing(on) {
    editing = on;
    document.body.classList.toggle('editing', on);
    panel.hidden = !on;
    items.forEach(it => { if (on) it.el.tabIndex = -1; else it.el.removeAttribute('tabindex'); });
    ptrs.clear();
    gesture = null;
    endGesture();
    if (on) {
      modal.classList.add('hidden');
      $('edMenu').hidden = true;
    } else if (dirty) {
      toast('저장하지 않은 변경사항은 새로고침하면 사라집니다');
    }
    sel = null;
    setView(1, 0, 0);
    applyAll();
    syncPanel();
    renderStatus();
  }

  /* ---------- 시작 ---------- */
  // img.decode()는 화면이 숨겨진 탭에서 끝나지 않는 경우가 있어 load 이벤트로 기다린다
  const ready = img => img.complete ? Promise.resolve() : new Promise(res => {
    img.addEventListener('load', res, { once: true });
    img.addEventListener('error', res, { once: true });
  });
  Promise.all(items.map(it => ready(it.img))).then(() => {
    items.forEach(it => {
      it.nw = it.img.naturalWidth || 1;
      it.nh = it.img.naturalHeight || 1;
      buildMask(it);
    });
    DEF = buildFixedDefaults() || buildDefaults();
    L = JSON.parse(JSON.stringify(DEF));
    const raw = storeGet();
    if (raw) {
      try {
        const obj = JSON.parse(raw);
        L = deserialize(obj).layout;
        savedAt = obj.savedAt || null;
      } catch (err) {
        console.warn('[배치 편집기] 저장된 배치를 읽지 못해 초기 배치를 사용합니다:', err.message);
      }
    }
    applyAll();
    renderStatus();
    editBtn.disabled = false;
  });

  // 이후 캐릭터 이동·충돌 판정에서 사용할 수 있도록 현재 배치를 노출
  window.IntpLayout = {
    ROOM_W, ROOM_H,
    get: () => (editBtn.disabled ? null : serialize()),
    isEditing: () => editing
  };
})();
