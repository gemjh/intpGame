package com.example.intpgame.data

import android.content.Context
import com.example.intpgame.engine.ContentLoader
import com.example.intpgame.engine.GameContent
import com.example.intpgame.engine.GameState
import com.example.intpgame.engine.StateCodec
import java.io.File

/**
 * assets 의 콘텐츠 JSON 읽기 + 앱 전용 저장소(filesDir)의 세이브 파일 읽기/쓰기.
 * 세이브는 앱 삭제 시 함께 지워지는 내부 저장소에 두므로 별도 권한이 필요 없다.
 */
class GameRepository(private val context: Context) {

    val content: GameContent by lazy {
        fun read(name: String): String =
            context.assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }
        ContentLoader.load(read("objects.json"), read("actions.json"), read("events.json"))
    }

    private val saveFile: File get() = File(context.filesDir, "save.json")

    /** 세이브가 없거나 깨졌으면 null (호출 측이 새 게임을 시작한다). */
    fun loadState(): GameState? {
        val f = saveFile
        if (!f.exists()) return null
        return try {
            StateCodec.decode(f.readText(Charsets.UTF_8), content)
        } catch (e: Exception) {
            null
        }
    }

    /** 임시 파일에 쓴 뒤 교체한다. 쓰는 도중 앱이 죽어도 기존 세이브가 깨지지 않는다. */
    fun saveState(json: String) {
        val target = saveFile
        val tmp = File(target.parentFile, "save.json.tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun deleteSave() {
        saveFile.delete()
    }
}
