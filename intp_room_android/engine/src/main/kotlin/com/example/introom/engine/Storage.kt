package com.example.introom.engine

import com.google.gson.Gson
import com.google.gson.GsonBuilder

// 세이브 데이터 직렬화(인코딩/디코딩)만 담당한다 -- "파일을 어디에 쓸지"는 플랫폼마다 다르므로
// (웹 버전은 app/data/saves 아래의 세션별 json, 안드로이드는 Context.filesDir) 이 순수 Kotlin/JVM
// 모듈에는 넣지 않고 app 모듈의 GameRepository 가 맡는다. 여기서는 GameState 와 JSON 문자열 사이의
// 변환만 제공해서 Android SDK 없이도(이 샌드박스에서) 라운드트립을 테스트할 수 있게 했다.
//
// Python 버전(app/engine/storage.py)은 dataclasses.asdict + json 모듈을 쓰지만, 코틀린에선
// Gson으로 같은 역할(중첩 데이터 클래스를 JSON으로/JSON에서)을 한다.
object StateCodec {
    private val gson: Gson = GsonBuilder().create()

    fun encode(state: GameState): String = gson.toJson(state)

    /**
     * v1 세이브(창문 관찰 필드 없음)도 그대로 읽힌다: GameState 는 모든 인자에 기본값이 있어 Gson 이
     * 기본 생성자로 만든 뒤 JSON 에 있는 필드만 덮어쓰므로, 새 필드는 빈 이력으로 시작한다.
     */
    fun decode(json: String): GameState =
        gson.fromJson(json, GameState::class.java).also { it.version = STATE_VERSION }
}
