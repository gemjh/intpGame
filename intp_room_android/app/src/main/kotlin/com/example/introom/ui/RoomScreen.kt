package com.example.introom.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * 방 화면 — 웹 버전 room_editor_src/intp-room-editor/src/{game.js, game-interact.js}를
 * Compose로 포팅했다. 가구 배치(assets/layout.json)는 수정하지 않고 그대로 읽어서 쓴다.
 *
 * 이 1차 포팅에서는 "배치 편집기"는 넣지 않았다 — 가구 위치는 이미 확정됐고(my-layout-261008.json),
 * 앱에서 또 옮길 일이 없기 때문이다. 대신 이동/충돌/가구 접근(가까이 가야 상호작용)과 실제 엔진
 * 연동(관찰→질문→탐구→가설→검증)은 웹 버전과 동일하게 들어있다.
 *
 * 0.2: 웹 버전과 같은 격자 BFS 길찾기(findPath)를 넣었다 — 직선으로 가면 이삿짐 상자에 막혀 창문에
 * 다가갈 수 없던 문제를 고친다.
 */

// 바닥(이동 가능 영역) — game.js의 FLOOR_POLY 그대로.
private val FLOOR_POLY = listOf(
    Offset(90f, 505f), Offset(648f, 325f), Offset(795f, 415f), Offset(795f, 598f), Offset(900f, 618f),
    Offset(1085f, 578f), Offset(1085f, 783f), Offset(905f, 783f), Offset(735f, 705f), Offset(650f, 668f), Offset(465f, 745f),
)

private const val PLAYER_R = 16f
private const val SPEED = 240f // room-px / 초
private const val FOOT_TOP = 0.6f
private const val FOOT_BOTTOM = 0.96f
private const val FOOT_INSET = 0.08f
private const val FRAME_TIME = 0.12f
private const val PROXIMITY_R = 220f

// 가구 id -> 엔진 오브젝트 id. game-interact.js의 FURNITURE_TO_OBJECT와 동일.
private val FURNITURE_TO_OBJECT = mapOf(
    "bed" to "bed", "desk" to "desk", "window" to "window", "boxes" to "boxes", "radio" to "radio",
    "mirror" to "mirror", "shelf" to "bookshelf", "board" to "memowall", "drawer" to "mail",
)

// 문은 가구 배치 JSON에 없고 배경 그림에 이미 그려져 있다. game-interact.js에서 서랍장과
// 히트박스가 겹치던 걸 고친 뒤의 좌표를 그대로 썼다 — 앵커(근접 판정/걸어갈 지점)와 보이는
// 히트박스 중심을 분리했다(앵커는 바닥 위, 히트박스는 서랍장과 안 겹치게 오른쪽으로).
private val DOOR_ANCHOR = Offset(1045f, 600f)
private val DOOR_HOTSPOT_CENTER = Offset(1055f, 560f)
private const val DOOR_HOTSPOT_W_FRAC = 0.08f
private const val DOOR_HOTSPOT_H_FRAC = 0.28f

private fun pointInPoly(x: Float, y: Float, poly: List<Offset>): Boolean {
    var inside = false
    var j = poly.size - 1
    for (i in poly.indices) {
        val xi = poly[i].x; val yi = poly[i].y
        val xj = poly[j].x; val yj = poly[j].y
        if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
        j = i
    }
    return inside
}

private fun circleHitsRect(cx: Float, cy: Float, r: Float, rect: RectPx): Boolean {
    val nx = cx.coerceIn(rect.left, rect.right)
    val ny = cy.coerceIn(rect.top, rect.bottom)
    val dx = cx - nx; val dy = cy - ny
    return dx * dx + dy * dy < r * r
}

private fun footprints(items: List<FurnitureItem>): List<RectPx> =
    items.filter { it.id in COLLIDABLE }.map { f ->
        val w = f.bounds.width; val h = f.bounds.height
        RectPx(
            left = f.bounds.left + w * FOOT_INSET, right = f.bounds.right - w * FOOT_INSET,
            top = f.bounds.top + h * FOOT_TOP, bottom = f.bounds.top + h * FOOT_BOTTOM,
        )
    }

private fun canStand(x: Float, y: Float, rects: List<RectPx>): Boolean {
    if (!pointInPoly(x, y, FLOOR_POLY)) return false
    return rects.none { circleHitsRect(x, y, PLAYER_R, it) }
}

// 가구를 캐릭터가 서 있는 자리로 옮기는 편집기가 이 앱엔 없지만(배치는 고정), 만약을 대비해
// game.js와 동일한 복구 로직을 유지한다 — 어떤 이유로든 현재 위치가 막혀 있으면 가장 가까운
// 빈 자리로 보정한다.
private fun findNearestFree(x: Float, y: Float, rects: List<RectPx>): Offset {
    if (canStand(x, y, rects)) return Offset(x, y)
    var r = 6f
    while (r <= 500f) {
        val steps = max(10, (r / 4).toInt())
        for (i in 0 until steps) {
            val a = (i.toFloat() / steps) * (2 * Math.PI).toFloat()
            val nx = x + cos(a) * r; val ny = y + sin(a) * r
            if (canStand(nx, ny, rects)) return Offset(nx, ny)
        }
        r += 6f
    }
    return Offset(x, y)
}

/* ---------- 길찾기 ----------
 * game.js 의 lineClear/findPath 를 그대로 옮겼다. 직선 이동만으로는 가구(특히 이삿짐 상자)에 막혀
 * 창문 앞까지 못 가는 문제가 있어서, 직선이 막혀 있으면 격자 BFS로 우회 경로를 찾는다. 목표 지점
 * 자체가 바닥 밖(벽에 걸린 창문 등)이면 도달 가능한 칸 중 목표에 가장 가까운 칸까지 간다. */
private const val CELL = 10

private fun lineClear(ax: Float, ay: Float, bx: Float, by: Float, rects: List<RectPx>): Boolean {
    val n = max(1, ceil(hypot(bx - ax, by - ay) / 4f).toInt())
    for (i in 1..n) {
        if (!canStand(ax + (bx - ax) * i / n, ay + (by - ay) * i / n, rects)) return false
    }
    return true
}

private fun findPath(sx: Float, sy: Float, tx: Float, ty: Float, rects: List<RectPx>): List<Offset> {
    if (canStand(tx, ty, rects) && lineClear(sx, sy, tx, ty, rects)) return listOf(Offset(tx, ty))
    val cols = ceil(ROOM_PX_WIDTH / CELL).toInt()
    val rows = ceil(ROOM_PX_HEIGHT / CELL).toInt()
    fun key(c: Int, r: Int) = r * cols + c
    fun center(c: Int, r: Int) = Offset(c * CELL + CELL / 2f, r * CELL + CELL / 2f)
    val sc = (sx / CELL).toInt(); val sr = (sy / CELL).toInt()
    val prev = HashMap<Int, Int>().apply { put(key(sc, sr), -1) }
    val queue = ArrayList<Pair<Int, Int>>().apply { add(sc to sr) }
    var best: Pair<Int, Int>? = null
    var bestD = Float.POSITIVE_INFINITY
    val dirs = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1, 1 to 1, 1 to -1, -1 to 1, -1 to -1)
    var qi = 0
    while (qi < queue.size) {
        val (c, r) = queue[qi++]
        val p = center(c, r)
        val d = hypot(p.x - tx, p.y - ty)
        if (d < bestD) { bestD = d; best = c to r }
        if (d < CELL) break
        for ((dc, dr) in dirs) {
            val nc = c + dc; val nr = r + dr
            if (nc < 0 || nr < 0 || nc >= cols || nr >= rows || prev.containsKey(key(nc, nr))) continue
            val np = center(nc, nr)
            if (!canStand(np.x, np.y, rects) || !lineClear(p.x, p.y, np.x, np.y, rects)) continue
            prev[key(nc, nr)] = key(c, r)
            queue.add(nc to nr)
        }
    }
    val b = best ?: return emptyList()
    val cells = ArrayList<Offset>()
    var k = key(b.first, b.second)
    while (k != -1) {
        cells.add(center(k % cols, k / cols))
        k = prev.getValue(k)
    }
    cells.reverse()
    if (canStand(tx, ty, rects)) cells.add(Offset(tx, ty))
    // 직선으로 갈 수 있는 중간 지점은 건너뛴다 (지그재그 방지)
    val path = ArrayList<Offset>()
    var from = Offset(sx, sy)
    for (i in cells.indices) {
        if (i == cells.size - 1 || !lineClear(from.x, from.y, cells[i + 1].x, cells[i + 1].y, rects)) {
            path.add(cells[i])
            from = cells[i]
        }
    }
    return path
}

private fun loadBitmap(assetFile: String, context: android.content.Context): ImageBitmap {
    context.assets.open(assetFile).use { stream ->
        return BitmapFactory.decodeStream(stream).asImageBitmap()
    }
}

private data class CharacterSprites(
    val idle: Map<String, ImageBitmap>,
    val walk: Map<String, List<ImageBitmap>>,
)

private fun loadCharacterSprites(context: android.content.Context): CharacterSprites {
    val dirs = listOf("front", "back", "left", "right")
    val idle = dirs.associateWith { loadBitmap("character/idle_$it.png", context) }
    val walk = dirs.associateWith { dir -> (1..4).map { loadBitmap("character/walk_${dir}_$it.png", context) } }
    return CharacterSprites(idle, walk)
}

@Composable
fun RoomScreen(viewModel: GameViewModel) {
    val context = LocalContext.current
    val furniture = remember { loadFurnitureLayout(context) }
    val images = remember { furniture.associate { it.id to loadBitmap(it.assetFile, context) } }
    val background = remember { loadBitmap("room.jpg", context) }
    val sprites = remember { loadCharacterSprites(context) }

    val anchorFor = remember(furniture) {
        val m = mutableMapOf<String, Offset>()
        for (f in furniture) {
            val objectId = FURNITURE_TO_OBJECT[f.id] ?: continue
            m[objectId] = Offset(f.x, f.y)
        }
        m["door"] = DOOR_ANCHOR
        m
    }

    var playerPos by remember { mutableStateOf(Offset(600f, 650f)) }
    var target by remember { mutableStateOf<Offset?>(null) }
    val path = remember { ArrayDeque<Offset>() } // 남은 경유 지점 (target 다음에 갈 곳들)
    var facing by remember { mutableStateOf("front") }
    var walkFrame by remember { mutableStateOf(0) }
    var isMoving by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showLog by remember { mutableStateOf(false) }

    val footRects = remember(furniture) { footprints(furniture) }

    fun walkTo(x: Float, y: Float) {
        path.clear()
        path.addAll(findPath(playerPos.x, playerPos.y, x, y, footRects))
        target = path.removeFirstOrNull()
    }

    LaunchedEffect(footRects) {
        var lastNanos = 0L
        var frameTimer = 0f
        while (isActive) {
            withFrameNanos { now ->
                if (lastNanos != 0L) {
                    val dt = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.05f)

                    if (!canStand(playerPos.x, playerPos.y, footRects)) {
                        playerPos = findNearestFree(playerPos.x, playerPos.y, footRects)
                        target = null
                        path.clear()
                    }

                    var moved = false
                    target?.let { t ->
                        val dx = t.x - playerPos.x; val dy = t.y - playerPos.y
                        val dist = hypot(dx, dy)
                        if (dist < 2f) {
                            target = path.removeFirstOrNull()
                        } else {
                            val step = min(dist, SPEED * dt)
                            val ux = dx / dist; val uy = dy / dist
                            facing = if (abs(ux) > abs(uy)) (if (ux > 0) "right" else "left") else (if (uy > 0) "front" else "back")
                            var nx = playerPos.x; var ny = playerPos.y
                            val cx = playerPos.x + ux * step
                            if (canStand(cx, playerPos.y, footRects)) { nx = cx; moved = true }
                            val cy = playerPos.y + uy * step
                            if (canStand(nx, cy, footRects)) { ny = cy; moved = true }
                            playerPos = Offset(nx, ny)
                            if (!moved) { target = null; path.clear() } // 완전히 막혔으면 포기
                        }
                    }

                    if (moved) {
                        frameTimer += dt
                        if (frameTimer >= FRAME_TIME) { frameTimer = 0f; walkFrame = (walkFrame + 1) % 4 }
                    } else {
                        frameTimer = 0f; walkFrame = 0
                    }
                    isMoving = moved

                    pending?.let { (objectId, label) ->
                        val anchor = anchorFor[objectId]
                        if (anchor != null && hypot(anchor.x - playerPos.x, anchor.y - playerPos.y) <= PROXIMITY_R) {
                            pending = null
                            viewModel.openObject(objectId, label)
                        } else if (target == null) {
                            pending = null
                        }
                    }
                }
                lastNanos = now
            }
        }
    }

    fun handleFurnitureTap(item: FurnitureItem) {
        val objectId = FURNITURE_TO_OBJECT[item.id] ?: return
        val anchor = Offset(item.x, item.y)
        if (hypot(anchor.x - playerPos.x, anchor.y - playerPos.y) <= PROXIMITY_R) {
            pending = null
            viewModel.openObject(objectId, item.name)
        } else {
            walkTo(anchor.x, anchor.y)
            pending = objectId to item.name
        }
    }

    fun handleDoorTap() {
        if (hypot(DOOR_ANCHOR.x - playerPos.x, DOOR_ANCHOR.y - playerPos.y) <= PROXIMITY_R) {
            pending = null
            viewModel.openObject("door", "문")
        } else {
            walkTo(DOOR_ANCHOR.x, DOOR_ANCHOR.y)
            pending = "door" to "문"
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(viewModel = viewModel, onLogClick = { showLog = true })

        Box(modifier = Modifier.fillMaxWidth().aspectRatio(ROOM_PX_WIDTH / ROOM_PX_HEIGHT)) {
            var boxSize by remember { mutableStateOf(IntSize.Zero) }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxSize = it }
                    .pointerInput(footRects) {
                        detectTapGestures { offset ->
                            if (boxSize.width == 0) return@detectTapGestures
                            val scale = boxSize.width / ROOM_PX_WIDTH
                            val rx = offset.x / scale; val ry = offset.y / scale
                            if (pointInPoly(rx, ry, FLOOR_POLY)) walkTo(rx, ry) // 바닥 밖 탭은 무시
                        }
                    },
            ) {
                val scale = if (boxSize.width > 0) boxSize.width / ROOM_PX_WIDTH else 0f
                Image(bitmap = background, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)

                if (scale > 0f) {
                    val density = LocalDensity.current

                    // 문 히트박스 (보이지 않음, 서랍장과 안 겹치게 오른쪽으로 옮긴 영역)
                    val doorWpx = ROOM_PX_WIDTH * DOOR_HOTSPOT_W_FRAC * scale
                    val doorHpx = ROOM_PX_HEIGHT * DOOR_HOTSPOT_H_FRAC * scale
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(((DOOR_HOTSPOT_CENTER.x * scale) - doorWpx / 2).toInt(), ((DOOR_HOTSPOT_CENTER.y * scale) - doorHpx / 2).toInt()) }
                            .size(with(density) { doorWpx.toDp() }, with(density) { doorHpx.toDp() })
                            .clickable { handleDoorTap() },
                    )

                    for (item in furniture) {
                        if (!images.containsKey(item.id)) continue
                        val wPx = item.w * scale; val hPx = item.h * scale
                        val leftPx = (item.x * scale) - wPx / 2
                        val topPx = (item.y * scale) - hPx / 2
                        Image(
                            bitmap = images.getValue(item.id),
                            contentDescription = item.name,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier
                                .offset { IntOffset(leftPx.toInt(), topPx.toInt()) }
                                .size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
                                .graphicsLayer { rotationZ = item.rotationDeg }
                                .clickable { handleFurnitureTap(item) },
                        )
                    }

                    // 캐릭터
                    val spriteList = if (isMoving) sprites.walk[facing] else null
                    val sprite = spriteList?.get(walkFrame) ?: sprites.idle.getValue(facing)
                    // 웹 버전 game.css의 .player{width:80px;height:102px} 그대로 (room-px 좌표계 기준).
                    // 2등신 40×48 스프라이트를 2배로 그린다.
                    val charWpx = 80f * scale
                    val charHpx = 102f * scale
                    val charLeftPx = (playerPos.x * scale) - charWpx / 2
                    val charTopPx = (playerPos.y * scale) - charHpx * 0.9f // 발 위치 기준 앵커(머리 위로 배치)
                    Image(
                        bitmap = sprite,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        filterQuality = FilterQuality.None, // 픽셀아트: 확대 시 보간 없이 또렷하게
                        modifier = Modifier
                            .offset { IntOffset(charLeftPx.toInt(), charTopPx.toInt()) }
                            .size(with(density) { charWpx.toDp() }, with(density) { charHpx.toDp() }),
                    )
                }
            }
        }
    }

    val dialog by viewModel.dialog.collectAsState()
    if (dialog.visible) {
        InteractionDialog(dialog = dialog, onClose = { viewModel.closeDialog() })
    }

    val endUi by viewModel.end.collectAsState()
    if (endUi.visible) {
        EndScreen(label = endUi.label, onRestart = { viewModel.restart() })
    }

    if (showLog) {
        LogDialog(lines = viewModel.recentLog(), onClose = { showLog = false })
    }
}

@Composable
private fun TopBar(viewModel: GameViewModel, onLogClick: () -> Unit) {
    val chrome by viewModel.chrome.collectAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("INTP 키우기", style = MaterialTheme.typography.titleMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(chrome.clockText, style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onLogClick) { Text("기록") }
        }
    }
}

@Composable
private fun InteractionDialog(dialog: DialogUi, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Card(
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(dialog.title, style = MaterialTheme.typography.titleLarge)
                if (dialog.body.isNotBlank()) {
                    Text(dialog.body, modifier = Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (dialog.sections.isNotEmpty()) {
                    LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 260.dp)) {
                        items(dialog.sections) { section -> ThreadSectionView(section) }
                    }
                }
                if (dialog.actions.isNotEmpty()) {
                    Column(modifier = Modifier.padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        dialog.actions.forEach { action ->
                            Button(onClick = action.onClick, modifier = Modifier.fillMaxWidth()) { Text(action.label) }
                        }
                    }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End).padding(top = 8.dp)) { Text("닫기") }
            }
        }
    }
}

@Composable
private fun ThreadSectionView(section: ThreadSectionUi) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        if (section.title.isNotBlank()) {
            Text(section.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        section.observations.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall) }
        section.infos.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        section.hypothesisText?.let {
            Text("$it [${section.hypothesisStatus}]", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun EndScreen(label: String, onRestart: () -> Unit) {
    Dialog(onDismissRequest = {}) {
        Card(shape = RoundedCornerShape(18.dp), modifier = Modifier.wrapContentSize()) {
            Column(modifier = Modifier.padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(label, style = MaterialTheme.typography.headlineSmall)
                Text("문을 열고 밖으로 나섰다.", modifier = Modifier.padding(top = 10.dp))
                Button(onClick = onRestart, modifier = Modifier.padding(top = 16.dp)) { Text("처음부터 다시 시작") }
            }
        }
    }
}

@Composable
private fun LogDialog(lines: List<String>, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Card(shape = RoundedCornerShape(18.dp)) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text("기록", style = MaterialTheme.typography.titleLarge)
                LazyColumn(modifier = Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(max = 320.dp)) {
                    items(lines) { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 2.dp)) }
                }
                TextButton(onClick = onClose, modifier = Modifier.align(Alignment.End)) { Text("닫기") }
            }
        }
    }
}
