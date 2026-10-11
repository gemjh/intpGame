package com.example.introom.data

import android.content.Context
import com.example.introom.engine.GameState
import com.example.introom.engine.StateCodec
import java.io.File

/*
 * 세이브 파일 읽기/쓰기. Python 버전(app/engine/storage.py)과 같은 역할이지만, 저장 위치만
 * 플랫폼에 맞게 바뀐다 — 서버의 세션별 JSON 파일 대신, 앱 내부 저장소(Context.filesDir)에
 * 기기당 하나의 세이브 파일을 둔다(이 앱은 서버 세션 개념이 없는 단일 기기용 오프라인 게임이라).
 * 임시 파일에 쓴 뒤 교체하는 원자적 저장 방식도 그대로 유지했다.
 */
class GameRepository(context: Context) {
    private val saveFile = File(context.filesDir, "intp_room_save.json")

    fun save(state: GameState) {
        val tmp = File(saveFile.parentFile, saveFile.name + ".tmp")
        tmp.writeText(StateCodec.encode(state))
        tmp.copyTo(saveFile, overwrite = true)
        tmp.delete()
    }

    fun load(): GameState? {
        if (!saveFile.exists()) return null
        return try {
            StateCodec.decode(saveFile.readText())
        } catch (e: Exception) {
            null
        }
    }
}
