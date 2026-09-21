package com.example.intpgame.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.intpgame.data.GameRepository
import com.example.intpgame.engine.GameEngine
import com.example.intpgame.engine.GameError
import com.example.intpgame.engine.GameSnapshot
import com.example.intpgame.engine.GameState
import com.example.intpgame.engine.StateCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 화면이 그리는 모든 것. message 는 한 번 보여준 뒤 [GameViewModel.consumeMessage] 로 비운다. */
data class UiState(
    val snapshot: GameSnapshot,
    val message: String? = null,
    val messageSeq: Long = 0L,
)

class GameViewModel(app: Application) : AndroidViewModel(app) {

    // 저장 순서 보장: 늦게 시작한 저장이 더 오래된 내용으로 덮어쓰지 않게 번호를 매긴다.
    // (초기화 순서 주의: state 초기화에서 save() 를 부르므로 이 필드들이 반드시 state 보다 먼저 선언돼야 한다.)
    private val saveLock = Any()
    private var requestedSeq = 0L
    private var writtenSeq = 0L

    private val repo = GameRepository(app)
    private val engine = GameEngine(repo.content)
    private var state: GameState = repo.loadState() ?: engine.newGame().also { save(it) }

    private val _ui = MutableStateFlow(UiState(engine.snapshot(state)))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    fun doAction(actionId: String) = step { engine.executeAction(state, actionId) }

    fun move(locationId: String) = step { engine.move(state, locationId) }

    fun chooseEvent(eventId: String, choiceIdx: Int) = step { engine.chooseEvent(state, eventId, choiceIdx) }

    fun reset() {
        state = engine.newGame()
        save(state)
        publish(null)
    }

    fun consumeMessage() {
        _ui.value = _ui.value.copy(message = null)
    }

    // ------------------------------------------------------------------
    /** 엔진 호출 -> 저장 -> 화면 갱신. 규칙 위반은 상태를 바꾸지 않고 메시지로만 알린다. */
    private fun step(block: () -> Unit) {
        try {
            block()
            save(state)
            publish(null)
        } catch (e: GameError) {
            publish(e.message ?: "할 수 없는 행동이야.")
        }
    }

    private fun publish(message: String?) {
        val prev = _ui.value
        _ui.value = UiState(
            snapshot = engine.snapshot(state),
            message = message,
            messageSeq = if (message != null) prev.messageSeq + 1 else prev.messageSeq,
        )
    }

    private fun save(s: GameState) {
        val json = StateCodec.encode(s)          // 메인 스레드에서 바로 직렬화(가변 상태를 IO 스레드에 넘기지 않는다)
        val seq = ++requestedSeq
        viewModelScope.launch(Dispatchers.IO) {
            synchronized(saveLock) {
                if (seq > writtenSeq) {
                    repo.saveState(json)
                    writtenSeq = seq
                }
            }
        }
    }
}
