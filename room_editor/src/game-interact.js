/* ===== INTP 키우기 — 가구 접근/상호작용 + 실제 엔진 연동 =====
 * game.js(이동/충돌) 다음에 실행된다. 여기서 하는 일은 두 가지뿐이다:
 *   ① 가구를 눌러도 멀리 있으면 그 자리로 걸어간 뒤에야 상호작용이 열리게 한다 ("가구 접근").
 *   ② 실제 관찰→질문→탐구→가설→검증 콘텐츠/로직은 전부 기존 FastAPI 엔진(/api/*)에서 그대로
 *      가져와서 보여준다 — 이 파일은 콘텐츠를 새로 만들지 않는다. 엔진(app/engine/*)은 전혀
 *      건드리지 않았고, 가구 좌표/배치도 여기서 바꾸지 않는다.
 *
 * 새 방의 가구 9종 vs 기존 엔진 오브젝트(11종: window/boxes/desk/computer/bookshelf/mirror/
 * bed/radio/memowall/mail/door) 매핑:
 *   shelf→bookshelf, board→memowall, drawer→mail 로 이름만 다르고 역할은 대응된다.
 *   desk는 책상 그림에 모니터가 같이 그려져 있어서(책상+컴퓨터를 한 가구로 취급) 책상을 누르면
 *   "책상에서 정리" + "컴퓨터로 검색"을 한 번에 보여준다.
 *   door(문)는 가구 배치 JSON에는 없고 방 배경 그림에 이미 그려져 있어서, 그 위치에 좌표만
 *   맞춘 보이지 않는 히트박스를 하나 추가했다(가구 배치 편집기에는 잡히지 않는다).
 */
(() => {
  'use strict';
  const $ = id => document.getElementById(id);
  const stage = $('stage');
  const modal = $('modal');
  const titleEl = $('title'), descEl = $('desc'), closeBtn = $('close'), observeBtn = $('observe');
  if (!stage || !modal || !window.IntpPlayer || !window.IntpLayout) return;

  const PROXIMITY_R = 220;   // room-px — "가구 근처"로 쳐주는 거리(1차 근사치, 방 전체가 1200x800)

  const FURNITURE_TO_OBJECT = {
    bed: 'bed', desk: 'desk', window: 'window', boxes: 'boxes', radio: 'radio',
    mirror: 'mirror', shelf: 'bookshelf', board: 'memowall', drawer: 'mail'
  };

  // 문: 방 배경 그림 속 문의 대략적인 위치(room-px). 가구처럼 드래그로 옮길 일이 없어 고정값.
  // 배경 그림상 문 위치는 (965,520) 부근이지만, 그 지점은 바닥(FLOOR_POLY) 밖(벽)이라 실제로는
  // 못 걸어가고, 히트박스도 바로 옆 "drawer"(편지 서랍장, bounds left:870 right:1002)와 겹쳐서
  // 문을 눌러도 서랍장이 열리는 문제가 있었다. 걸어갈 앵커는 바닥 위 매트 지점으로,
  // 클릭 히트박스는 서랍장 bounds(≤1002)와 안 겹치게 오른쪽으로 옮겼다.
  const DOOR_POS = { x: 1045, y: 600 };        // 상호작용 앵커(근접 판정 + walkTo 목적지) — 바닥 위
  const DOOR_HOTSPOT_CENTER = { x: 1055, y: 560 };   // 보이는 히트박스 중심 — 문+매트, 서랍장과 안 겹침

  let pendingInteract = null;   // { objectId, label } — 걸어가는 중인 상호작용 목표
  let snap = null;              // 서버 스냅샷(최신 상태)

  /* ---------- 문 히트박스 ---------- */
  // .furniture 클래스를 쓰지 않는다 — 배치 편집기(editor.js)의 items는 초기화 시점에 이미
  // .furniture 요소들을 스냅샷으로 떠가기 때문에, 지금 추가해도 편집 대상에는 섞이지 않는다.
  // 그래도 혹시 모르니 별도 클래스로 완전히 분리해 둔다.
  const world = stage.querySelector('.world') || stage;
  const doorHotspot = document.createElement('div');
  doorHotspot.className = 'ix-door';
  doorHotspot.dataset.key = 'door';
  doorHotspot.style.left = (DOOR_HOTSPOT_CENTER.x / 1200 * 100) + '%';
  doorHotspot.style.top = (DOOR_HOTSPOT_CENTER.y / 800 * 100) + '%';
  world.appendChild(doorHotspot);

  /* ---------- 좌표 / 근접 판정 ---------- */
  function objectAnchor(objectId) {
    if (objectId === 'door') return DOOR_POS;
    const data = window.IntpLayout.get();
    if (!data) return null;
    const fid = Object.keys(FURNITURE_TO_OBJECT).find(k => FURNITURE_TO_OBJECT[k] === objectId);
    const f = fid && data.furniture.find(x => x.id === fid);
    return f ? { x: f.x, y: f.y } : null;
  }

  function nearEnough(objectId) {
    const a = objectAnchor(objectId);
    if (!a) return false;
    const p = window.IntpPlayer.getPos();
    return Math.hypot(a.x - p.x, a.y - p.y) <= PROXIMITY_R;
  }

  /* ---------- 서버 통신 ---------- */
  function refreshChrome() {
    if (!snap) return;
    const clock = $('clock');
    if (clock) clock.textContent = snap.day + '일차 · ' + snap.period_label;
  }

  async function apiState() {
    const res = await fetch('/api/state', { credentials: 'same-origin' });
    snap = await res.json();
    refreshChrome();
  }

  async function apiInteract(object, action, payload) {
    // player_pos는 보내지 않는다: 서버의 근접 검증은 이 프로젝트의 옛 타일맵 좌표계 기준이라
    // 지금의 room-px 좌표와 안 맞는다. 대신 "충분히 가까이 와야 상호작용 버튼 자체가 뜬다"는
    // 걸 클라이언트(이 파일)가 이미 보장한다 — player_pos를 생략하면 서버는 근접 검사를 건너뛴다.
    const res = await fetch('/api/interact', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      credentials: 'same-origin', body: JSON.stringify({ object, action, ...(payload || {}) })
    });
    const data = await res.json();
    if (!res.ok) return { error: (data && data.error) || '안 됐다.' };
    snap = data.snapshot;
    refreshChrome();
    if (data.result && data.result.ended) showEndScreen(data.result.end_label);
    return data.result;
  }

  // 원본 "다음 시간 →" 버튼은 가짜로 시간을 넘기던 것이라 실제 엔진과는 무관해졌다 — 숨긴다.
  // (시간은 이제 각 행동의 turn_cost로, 서버에서 실제로 진행된다.)
  const nextBtn = $('next');
  if (nextBtn) nextBtn.style.display = 'none';

  // "기록" 버튼: 원본은 로컬 logs 배열을 보여줬는데, 이제 가구 클릭을 전부 가로채므로 그 배열은
  // 더 이상 채워지지 않는다 — 서버가 들고 있는 진짜 로그를 보여주도록 클릭 핸들러를 바꾼다.
  const logBtn = $('log');
  if (logBtn) {
    const fresh = logBtn.cloneNode(true);   // 원본 핸들러 제거
    logBtn.parentNode.replaceChild(fresh, logBtn);
    fresh.addEventListener('click', () => {
      openSimple('기록', snap && snap.log.length ? snap.log.slice().reverse().join('\n') : '아직 기록이 없다.');
    });
  }

  /* ---------- 모달 확장 (기존 #title/#desc/#observe/#close DOM을 그대로 쓰고, 둘만 추가) ---------- */
  const extra = document.createElement('div');
  extra.className = 'ix-extra';
  descEl.insertAdjacentElement('afterend', extra);
  const actionsRow = document.createElement('div');
  actionsRow.className = 'ix-actions';
  observeBtn.insertAdjacentElement('beforebegin', actionsRow);
  observeBtn.style.display = 'none';   // 원본 "관찰하기"는 실제 행동 목록으로 대체됐다

  function openSimple(title, text) {
    titleEl.textContent = title;
    descEl.textContent = text;
    extra.innerHTML = '';
    actionsRow.innerHTML = '';
    modal.classList.remove('hidden');
  }

  function addActionButton(label, onClick) {
    const b = document.createElement('button');
    b.textContent = label;
    b.addEventListener('click', onClick);
    actionsRow.appendChild(b);
    return b;
  }

  // 기존 Python 웹 버전(app/static/room.js)의 OBJECT_ACTIONS를 그대로 옮긴 것 — 콘텐츠는 새로
  // 만들지 않고 동일하게 유지한다.
  function actionsFor(objectId) {
    if (!snap) return [];
    const hasBinoculars = snap.items.includes('쌍안경');
    switch (objectId) {
      case 'window': {
        const zoom = { action: 'zoom', label: '쌍안경으로 확대해서 보기 (1턴)' };
        const base = snap.window_resolution >= 1 ? [
          { action: 'glance', label: '잠깐 보기 (0턴)' },
          { action: 'observe', label: '바다 관찰하기', payload: { region: 'sea' } },
          { action: 'observe', label: '하늘 관찰하기', payload: { region: 'sky' } },
          { action: 'observe', label: '앞마당 관찰하기', payload: { region: 'yard' } },
          { action: 'observe', label: '횟집 관찰하기', payload: { region: 'restaurant' } },
        ] : [
          { action: 'glance', label: '잠깐 보기 (0턴)' },
          { action: 'observe', label: '관찰하기 (1턴)' },
        ];
        return hasBinoculars ? [...base, zoom] : base;
      }
      case 'boxes': return [{ action: 'organize', label: '정리하기 (1턴)' }];
      case 'bookshelf': return [
        { action: 'skim', label: '책등 훑어보기 (0턴)' },
        { action: 'read', label: '집중해서 읽기 (1턴)' },
      ];
      case 'mirror': return [{ action: 'glance', label: '거울 보기 (0턴)' }];
      case 'bed': return [{ action: 'lie_down', label: '누워서 생각하기 (0턴)' }];
      case 'radio': return [{ action: 'listen', label: '라디오 듣기 (0턴)' }];
      case 'mail': return [{ action: 'check', label: '우편물 확인 (0턴)' }];
      case 'door': return [{ action: 'exit', label: (snap.exit_unlocked ? '확인하러 간다' : '나간다') }];
      default: return [];
    }
  }

  async function runSimpleAction(objectId, act) {
    const result = await apiInteract(objectId, act.action, act.payload);
    descEl.textContent = (result && result.error) ? '(' + result.error + ')' : ((result && result.text) || '');
  }

  function openObject(objectId, label) {
    titleEl.textContent = label;
    descEl.textContent = '';
    extra.innerHTML = '';
    actionsRow.innerHTML = '';
    modal.classList.remove('hidden');

    if (objectId === 'desk') { renderDesk(); return; }
    if (objectId === 'memowall') { renderMemowall(); return; }

    const acts = actionsFor(objectId);
    if (!acts.length) { descEl.textContent = '지금은 딱히 할 게 없다.'; return; }
    acts.forEach(act => addActionButton(act.label, () => runSimpleAction(objectId, act)));
  }

  // 책상(+컴퓨터): 스레드별 관찰/정보/가설을 보여주고, 가설을 고르거나 재검토할 수 있다.
  function renderDesk() {
    descEl.textContent = '책상 위를 정리해 본다.';
    extra.innerHTML = '';
    actionsRow.innerHTML = '';

    const q = snap.computer_searches.night_light ? snap.computer_searches.night_light.query : null;
    addActionButton(q ? `컴퓨터로 '${q}' 검색하기 (1턴)` : '컴퓨터로 검색하기 (1턴)', async () => {
      const r = await apiInteract('computer', 'search', { thread: 'night_light' });
      if (r && r.error) { descEl.textContent = '(' + r.error + ')'; return; }
      renderDesk();
    });

    const threadIds = Object.keys(snap.desk);
    if (threadIds.length === 0) {
      const p = document.createElement('p');
      p.textContent = '아직 책상 위에 정리할 게 없다. 뭔가 관찰부터 해야 할 것 같다.';
      extra.appendChild(p);
      return;
    }
    threadIds.forEach(tid => {
      const t = snap.desk[tid];
      const block = document.createElement('div');
      block.className = 'ix-thread';
      let html = '<h5>관찰</h5>';
      html += t.observations.length
        ? t.observations.map(o => `<div class="ix-entry">· ${o.text}</div>`).join('')
        : '<div class="ix-entry">(아직 없음)</div>';
      html += '<h5>정보</h5>';
      html += t.infos.length
        ? t.infos.map(i => `<div class="ix-entry">[${i.source === 'book' ? '책' : '컴퓨터'}] ${i.text}</div>`).join('')
        : '<div class="ix-entry">(아직 없음)</div>';
      html += '<h5>가설</h5>';
      html += t.hypothesis
        ? `<div class="ix-hyp">${t.hypothesis.text} <span class="ix-hyp-status">[${t.hypothesis.status}]</span></div>`
        : '<div class="ix-entry">아직 가설이 없다.</div>';
      block.innerHTML = html;
      extra.appendChild(block);

      if (!t.hypothesis && t.observations.length > 0) {
        const templates = (snap.hypothesis_templates && snap.hypothesis_templates[tid]) || [];
        templates.forEach((tpl, idx) => {
          addActionButton(`가설로 적기: "${tpl.text}"`, async () => {
            await apiInteract('desk', 'hypothesis', { thread: tid, template_index: idx });
            renderDesk();
          });
        });
      } else if (t.hypothesis && t.observations.length > 0) {
        addActionButton('다시 검토하기', async () => {
          await apiInteract('desk', 'hypothesis', { thread: tid });
          renderDesk();
        });
      }
    });
  }

  function renderMemowall() {
    apiInteract('memowall', 'check', {}).then(() => {
      descEl.textContent = '';
      extra.innerHTML = (snap.memo_wall && snap.memo_wall.length)
        ? snap.memo_wall.map(q => `<div class="ix-entry">· ${q}</div>`).join('')
        : '<p>메모벽은 아직 비어 있다.</p>';
    });
  }

  /* ---------- 엔드 스크린 ---------- */
  let endScreen = null;
  function showEndScreen(label) {
    if (!endScreen) {
      endScreen = document.createElement('div');
      endScreen.className = 'ix-end';
      endScreen.innerHTML = '<div class="ix-end-panel"><h2></h2><p>문을 열고 밖으로 나섰다.</p>' +
        '<button type="button">처음부터 다시 시작</button></div>';
      document.body.appendChild(endScreen);
      endScreen.querySelector('button').addEventListener('click', async () => {
        const res = await fetch('/api/new', { method: 'POST', credentials: 'same-origin' });
        snap = await res.json();
        refreshChrome();
        endScreen.classList.remove('show');
        modal.classList.add('hidden');
      });
    }
    endScreen.querySelector('h2').textContent = label || 'Vertical Slice End';
    endScreen.classList.add('show');
  }

  /* ---------- 가구/문 클릭 가로채기 (원본 인라인 스크립트의 즉시-관찰 모달보다 먼저, 캡처 단계) ---------- */
  stage.addEventListener('click', e => {
    if (window.IntpLayout.isEditing()) return;
    const el = e.target.closest('.furniture, .ix-door');
    if (!el) return;
    e.stopPropagation();
    e.preventDefault();
    const key = el.dataset.key;
    const objectId = key === 'door' ? 'door' : FURNITURE_TO_OBJECT[key];
    if (!objectId) return;
    const label = key === 'door' ? '문' : (el.getAttribute('aria-label') || key);

    if (nearEnough(objectId)) {
      pendingInteract = null;
      openObject(objectId, label);
    } else {
      const a = objectAnchor(objectId);
      if (a) window.IntpPlayer.walkTo(a.x, a.y);
      pendingInteract = { objectId, label };
    }
  }, true);

  // 바닥을 눌러 다른 곳으로 이동을 시작하면, 가구로 걸어가던 건 취소한다.
  stage.addEventListener('pointerdown', e => {
    if (window.IntpLayout.isEditing()) return;
    if (e.target.closest && e.target.closest('.furniture, .ix-door')) return;
    pendingInteract = null;
  }, { capture: true, passive: true });

  (function pollProximity() {
    if (pendingInteract) {
      if (nearEnough(pendingInteract.objectId)) {
        const { objectId, label } = pendingInteract;
        pendingInteract = null;
        openObject(objectId, label);
      } else if (!window.IntpPlayer.isMoving()) {
        pendingInteract = null;   // 가는 길이 막혀 더는 못 간다 — 포기
      }
    }
    requestAnimationFrame(pollProximity);
  })();

  apiState();
})();
