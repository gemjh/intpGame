package com.example.introom.ui

import android.content.Context
import android.graphics.BitmapFactory
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * 가구 배치 로더 — assets/layout.json(= 웹 버전의 my-layout-261008.json, 그대로 복사)을 읽어서
 * 화면에 그릴 가구 목록을 만든다. room_editor_src/intp-room-editor/src/editor.js 의
 * deserialize()+serialize() 로직(비율 보정 + 회전 반영 축 정렬 바운딩박스 계산)을 그대로 포팅했다 —
 * 가구 위치/크기는 그 JSON이 기준이고 여기서 임의로 바꾸지 않는다(사용자 지시사항).
 *
 * 방 좌표계는 1200x800 room-px (웹 버전과 동일) — 화면에 그릴 때 화면 크기에 맞게 균일 스케일만 한다.
 */

const val ROOM_PX_WIDTH = 1200f
const val ROOM_PX_HEIGHT = 800f

data class RectPx(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class FurnitureItem(
    val id: String,
    val name: String,
    val assetFile: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val rotationDeg: Float,
    val z: Int,
    val bounds: RectPx, // 회전을 반영한 축 정렬 외곽 사각형 (충돌/렌더 크기 판정용)
)

// 가구 id -> 번들된 이미지 파일명 (room_editor_src/intp-room-editor/assets/*-new.png 와 동일한 그림을 쓴다)
private val ASSET_FILE = mapOf(
    "bed" to "bed.png", "boxes" to "boxes.png", "drawer" to "drawer.png", "radio" to "radio.png",
    "shelf" to "shelf.png", "mirror" to "mirror.png", "window" to "window.png", "board" to "board.png",
    "desk" to "desk.png",
)

// 가구 이름 한글 표시 (JSON에 name이 있지만, 혹시 몰라 기본값도 둔다)
private val DEFAULT_NAME = mapOf(
    "bed" to "침대", "boxes" to "이삿짐 상자", "drawer" to "편지 서랍장", "radio" to "라디오",
    "shelf" to "책장", "mirror" to "거울", "window" to "창문", "board" to "메모벽", "desk" to "책상",
)

// 바닥에 서 있는 가구만 충돌시킨다(웹 버전 game.js의 COLLIDABLE 과 동일 — 거울은 받침대가 있는
// 전신 스탠드 거울이라 포함, 라디오는 책상/책장 위 소품으로 보고 제외).
val COLLIDABLE: Set<String> = setOf("bed", "desk", "boxes", "drawer", "shelf", "mirror")

fun loadFurnitureLayout(context: Context): List<FurnitureItem> {
    val json = context.assets.open("layout.json").bufferedReader().use { it.readText() }
    val root = JSONObject(json)
    val arr = root.getJSONArray("furniture")

    val result = mutableListOf<FurnitureItem>()
    for (i in 0 until arr.length()) {
        val f = arr.getJSONObject(i)
        val id = f.getString("id")
        val assetFile = ASSET_FILE[id] ?: continue
        val name = if (f.has("name")) f.getString("name") else DEFAULT_NAME[id] ?: id

        val jsonW = f.getDouble("width").toFloat()
        val jsonH = f.getDouble("height").toFloat()
        val x = f.getDouble("x").toFloat()
        val y = f.getDouble("y").toFloat()
        val rotation = if (f.has("rotation")) f.getDouble("rotation").toFloat() else 0f
        val aspectLocked = !f.has("aspectLocked") || f.getBoolean("aspectLocked")
        val z = if (f.has("z")) f.getInt("z") else i + 1

        // 이미지가 교체되어 원본과 가로세로 비율이 달라졌으면(비율 유지 상태일 때) 면적은 유지하고
        // 새 비율로 맞춘다 -- editor.js의 deserialize()와 완전히 같은 식.
        val (nw, nh) = imageNaturalSize(context, assetFile)
        var w = jsonW
        var h = jsonH
        if (aspectLocked && nw > 0 && nh > 0) {
            val ar = nw.toFloat() / nh.toFloat()
            if (abs(w / h - ar) > 0.02f * ar) {
                w = sqrt(w * h * ar)
                h = w / ar
            }
        }

        // 회전을 반영한 축 정렬 외곽 사각형 (editor.js serialize()의 hw/hh 공식과 동일)
        val t = rotation * Math.PI.toFloat() / 180f
        val hw = (abs(w * cos(t)) + abs(h * sin(t))) / 2f
        val hh = (abs(w * sin(t)) + abs(h * cos(t))) / 2f
        val bounds = RectPx(left = x - hw, top = y - hh, right = x + hw, bottom = y + hh)

        result.add(FurnitureItem(id, name, assetFile, x, y, w, h, rotation, z, bounds))
    }
    return result.sortedBy { it.z }
}

private fun imageNaturalSize(context: Context, assetFile: String): Pair<Int, Int> {
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(assetFile).use { BitmapFactory.decodeStream(it, null, opts) }
    return Pair(opts.outWidth, opts.outHeight)
}
