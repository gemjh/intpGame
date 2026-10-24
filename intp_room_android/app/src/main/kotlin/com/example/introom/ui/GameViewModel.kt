package com.example.introom.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.introom.data.GameRepository
import com.example.introom.engine.COMPUTER_SEARCHES
import com.example.introom.engine.GameError
import com.example.introom.engine.GameState
import com.example.introom.engine.HYPOTHESIS_TEMPLATES
import com.example.introom.engine.PERIOD_LABELS
import com.example.introom.engine.WINDOW_ANY
import com.example.introom.engine.WINDOW_SUBJECTS
import com.example.introom.engine.QUESTION_STATE_LABELS
import com.example.introom.engine.WAVE_INTERPRETATIONS
import com.example.introom.engine.WAVE_THREAD
import com.example.introom.engine.canInterpret
import com.example.introom.engine.interact
import com.example.introom.engine.newGame
import com.example.introom.engine.recordsView
import com.example.introom.engine.waveAvailable
import com.example.introom.engine.windowSubjectLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/*
 * 엔진(:engine 모듈 — 웹 버전 app/engine 아래의 python 모듈들을 포트한 것)을 호출하고, 화면(RoomScreen.kt)이 그릴
 * 상태(StateFlow)로 바꿔주는 역할만 한다. 콘텐츠/판정 로직은 전혀 안 들어있다 — 전부 engine 쪽에서
 * 그대로 가져온다. room_editor_src/intp-room-editor/src/game-interact.js(가구 접근 + 엔진 연동)의
 * 로직을 그대로 옮겼다 — 다만 여기선 HTTP 호출(fetch) 대신 같은 프로세스 안의 Kotlin 함수 호출이라
 * 서버 없이 바로 동작한다 (웹 버전과 달리 player_pos 근접 검증도 애초에 거치지 않는다 — 클라이언트
 * 쪽 PROXIMITY_R 근접 판정이 그 역할을 대신한다. 웹 버전과 동일한 설계).
 */

data class ChromeUi(val clockText: String = "")

data class DialogAction(val label: String, val onClick: () -> Unit)

data class ThreadSectionUi(
    val title: String,
    val observations: List<String>,
    val infos: List<String>,
    val hypothesisText: String?,
    val hypothesisStatus: String?,
)

data class DialogUi(
    val visible: Boolean = false,
    val title: String = "",
    val body: String = "",
    val sections: List<ThreadSectionUi> = emptyList(),
    val actions: List<DialogAction> = emptyList(),
)

data class EndUi(val visible: Boolean = false, val label: String = "")

/** REC-03 기록 화면 한 줄. 질문 줄에는 바꿀 수 있는 상태 버튼(라벨, 상태ID)이 붙는다. */
data class RecordRowUi(val text: String, val buttons: List<Pair<String, () -> Unit>> = emptyList())

/** REC-03 기록 화면: 일지·관찰·질문·지식·연결 탭. 열람·질문 상태 변경 모두 0턴. */
data class RecordsUi(val tab: String = "log", val rows: List<RecordRowUi> = emptyList())

val RECORD_TABS = listOf("log" to "일지", "observations" to "관찰", "questions" to "질문", "knowledge" to "지식", "connections" to "연결")

private val THREAD_TITLES = mapOf("night_light" to "밤바다 불빛", "moon" to "낮의 달", "restaurant" to "횟집", WAVE_THREAD to "파도 주기")

class GameViewModel(application: Application) : AndroidViewModel(application) {
    private val repo = GameRepository(application)
    private var state: GameState = repo.load() ?: newGame()

    private val _chrome = MutableStateFlow(ChromeUi())
    val chrome: StateFlow<ChromeUi> = _chrome.asStateFlow()

    private val _dialog = MutableStateFlow(DialogUi())
    val dialog: StateFlow<DialogUi> = _dialog.asStateFlow()

    private val _end = MutableStateFlow(EndUi())
    val end: StateFlow<EndUi> = _end.asStateFlow()

    private val _records = MutableStateFlow<RecordsUi?>(null)
    val records: StateFlow<RecordsUi?> = _records.asStateFlow()

    init {
        refreshChrome()
    }

    private fun refreshChrome() {
        _chrome.value = ChromeUi(clockText = "${state.day}일차 · ${PERIOD_LABELS[state.period] ?: state.period}")
    }

    private fun persist() {
        viewModelScope.launch { repo.save(state) }
    }

    private fun doInteract(objectId: String, action: String, payload: Map<String, Any?> = emptyMap()): Map<String, Any?>? {
        return try {
            val result = interact(state, objectId, action, payload = payload)
            refreshChrome()
            persist()
            if (result["ended"] == true) {
                _end.value = EndUi(visible = true, label = result["end_label"] as? String ?: "Vertical Slice End")
            }
            result
        } catch (e: GameError) {
            mapOf("error" to e.message)
        }
    }

    fun closeDialog() {
        _dialog.update { it.copy(visible = false) }
    }

    fun restart() {
        state = newGame()
        persist()
        refreshChrome()
        _end.value = EndUi()
        _dialog.value = DialogUi()
    }

    // ------------------------------------------------------------------ 오브젝트별 행동 목록
    // game-interact.js의 actionsFor()를 그대로 옮겼다 (room.js OBJECT_ACTIONS 가 원출처).
    private fun actionsForObject(objectId: String): List<Triple<String, String, Map<String, Any?>>> {
        val hasBinoculars = "쌍안경" in state.items
        return when (objectId) {
            "window" -> {
                // 명세서 261011 WIN-01: 대상을 고르는 것 자체는 턴을 쓰지 않고, 관찰을 실행하면 정확히 1턴.
                // 강아지 라벨은 이름을 알게 된 뒤 '두식이'로 바뀐다 (windowSubjectLabel).
                val subjects = WINDOW_SUBJECTS.map { sid ->
                    Triple("observe", "${windowSubjectLabel(state, sid)} 보기 (1턴)", mapOf<String, Any?>("subject" to sid))
                }
                var acts = subjects + Triple("observe", "그냥 바라본다 (1턴)", mapOf<String, Any?>("subject" to WINDOW_ANY))
                if (hasBinoculars) acts = acts + Triple("zoom", "쌍안경으로 확대해서 보기 (1턴)", emptyMap())
                // 2차: 파도 주기 탐구. 파도를 한 번 눈여겨본 뒤부터 보인다 (질문 등록 없이도 가능).
                if (waveAvailable(state)) {
                    acts = acts + Triple("wave_count", "파도를 세어 본다 (1턴)", emptyMap()) +
                        Triple("wave_measure", "기준점을 정해 파도 간격을 잰다 (1턴)", emptyMap())
                }
                acts
            }
            "boxes" -> listOf(Triple("organize", "정리하기 (1턴)", emptyMap()))
            "bookshelf" -> listOf(
                Triple("skim", "책등 훑어보기 (0턴)", emptyMap()),
                Triple("read", "집중해서 읽기 (1턴)", emptyMap()),
            )
            "mirror" -> listOf(Triple("glance", "거울 보기 (0턴)", emptyMap()))
            "bed" -> listOf(Triple("lie_down", "누워서 생각하기 (0턴)", emptyMap()))
            "radio" -> listOf(Triple("listen", "라디오 듣기 (0턴)", emptyMap()))
            "mail" -> listOf(Triple("check", "우편물 확인 (0턴)", emptyMap()))
            "door" -> listOf(Triple("exit", if (state.exitUnlocked) "확인하러 간다" else "나간다", emptyMap()))
            else -> emptyList()
        }
    }

    private fun runSimpleAction(objectId: String, action: String, payload: Map<String, Any?>) {
        val result = doInteract(objectId, action, payload)
        val text = (result?.get("error") as? String)?.let { "($it)" } ?: (result?.get("text") as? String) ?: ""
        _dialog.update { it.copy(body = text) }
        @Suppress("UNCHECKED_CAST")
        (result?.get("questions") as? List<Map<String, String>>)?.forEach { offerQuestion(it.getValue("id"), it.getValue("text")) }
    }

    // QST-01: 관찰·행동으로 처음 떠오른 준비된 질문. 붙잡을지(0턴) 그냥 둘지 플레이어가 고른다.
    // 그냥 둬도 기록 화면의 '질문' 탭에서 나중에 붙잡을 수 있다.
    private fun offerQuestion(qid: String, text: String) {
        val base = _dialog.value
        lateinit var holdAction: DialogAction
        lateinit var skipAction: DialogAction
        fun dropOffer(extra: String) = _dialog.update {
            it.copy(body = it.body + extra, actions = it.actions.filter { a -> a !== holdAction && a !== skipAction })
        }
        holdAction = DialogAction("질문으로 붙잡기 (0턴)") {
            doInteract("records", "question", mapOf("question" to qid, "question_state" to "held"))
            dropOffer("\n붙잡았다.")
        }
        skipAction = DialogAction("그냥 둔다") { dropOffer("") }
        _dialog.value = base.copy(body = base.body + "\n\n떠오른 질문 — $text", actions = listOf(holdAction, skipAction) + base.actions)
    }

    fun openObject(objectId: String, label: String) {
        when (objectId) {
            "desk" -> renderDesk(label)
            "memowall" -> renderMemowall(label)
            else -> {
                val acts = actionsForObject(objectId)
                _dialog.value = DialogUi(
                    visible = true, title = label,
                    body = if (acts.isEmpty()) "지금은 딱히 할 게 없다." else "",
                    actions = acts.map { (action, actLabel, payload) ->
                        DialogAction(actLabel) { runSimpleAction(objectId, action, payload) }
                    },
                )
            }
        }
    }

    // 책상(+컴퓨터): 스레드별 관찰/정보/가설을 보여주고, 가설을 고르거나 재검토할 수 있다.
    // game-interact.js의 renderDesk()를 그대로 옮겼다 — 책상 그림에 모니터가 같이 그려져 있어서
    // "책상 정리"와 "컴퓨터 검색"을 한 화면에서 같이 보여준다 (desk+computer 오브젝트 병합).
    private fun renderDesk(label: String) {
        val desk = com.example.introom.engine.deskView(state)
        val query = COMPUTER_SEARCHES["night_light"]?.query
        val actions = mutableListOf<DialogAction>()
        actions.add(
            DialogAction(if (query != null) "컴퓨터로 '$query' 검색하기 (1턴)" else "컴퓨터로 검색하기 (1턴)") {
                val r = doInteract("computer", "search", mapOf("thread" to "night_light"))
                if (r?.get("error") != null) {
                    _dialog.update { it.copy(body = "(${r["error"]})") }
                } else {
                    renderDesk(label)
                }
            },
        )

        val sections = mutableListOf<ThreadSectionUi>()
        // 2차: 파도 주기 탐구의 해석/검증. 잰 기록을 보여주고 해석을 고르게 한다 (1턴, 정답을 확정하지 않음).
        if (canInterpret(state)) {
            val wave = desk[WAVE_THREAD]
            val records = state.actionsDone["wave_measure"]?.history.orEmpty().map {
                "잰 값: ${it.day}일차 ${PERIOD_LABELS[it.period]} 약 ${it.seconds}초"
            } + state.actionsDone["wave_count"]?.history.orEmpty().map {
                "어림: ${it.day}일차 ${PERIOD_LABELS[it.period]} ${it.low}~${it.high}초쯤"
            }
            sections.add(
                ThreadSectionUi(
                    title = "파도 주기", observations = records,
                    infos = wave?.infos.orEmpty().map { "[책] ${it["text"]}" },
                    hypothesisText = wave?.hypothesis?.get("text") as? String,
                    hypothesisStatus = wave?.hypothesis?.get("status_label") as? String,
                ),
            )
            WAVE_INTERPRETATIONS.forEachIndexed { idx, claim ->
                actions.add(
                    DialogAction("파도 기록 해석: \"$claim\" (1턴)") {
                        val r = doInteract("desk", "wave_interpret", mapOf("choice" to idx))
                        renderDesk(label)
                        _dialog.update { it.copy(body = (r?.get("error") as? String)?.let { e -> "($e)" } ?: (r?.get("text") as? String ?: "")) }
                    },
                )
            }
        }
        for ((threadId, view) in desk) {
            if (threadId == WAVE_THREAD) continue
            sections.add(
                ThreadSectionUi(
                    title = THREAD_TITLES[threadId] ?: threadId,
                    observations = view.observations.map { it["text"] as String },
                    infos = view.infos.map { "[${if (it["source"] == "book") "책" else "컴퓨터"}] ${it["text"]}" },
                    hypothesisText = (view.hypothesis?.get("text") as? String),
                    hypothesisStatus = (view.hypothesis?.get("status_label") as? String),
                ),
            )
            val hasHypothesis = view.hypothesis != null
            if (!hasHypothesis && view.observations.isNotEmpty()) {
                val templates = HYPOTHESIS_TEMPLATES[threadId].orEmpty()
                templates.forEachIndexed { idx, tpl ->
                    actions.add(
                        DialogAction("가설로 적기: \"${tpl.text}\"") {
                            doInteract("desk", "hypothesis", mapOf("thread" to threadId, "template_index" to idx))
                            renderDesk(label)
                        },
                    )
                }
            } else if (hasHypothesis && view.observations.isNotEmpty()) {
                actions.add(
                    DialogAction("다시 검토하기") {
                        doInteract("desk", "hypothesis", mapOf("thread" to threadId))
                        renderDesk(label)
                    },
                )
            }
        }

        _dialog.value = DialogUi(
            visible = true, title = label, body = "책상 위를 정리해 본다.",
            sections = if (sections.isEmpty()) {
                listOf(ThreadSectionUi("", emptyList(), emptyList(), null, null))
            } else sections,
            actions = actions,
        )
    }

    private fun renderMemowall(label: String) {
        val result = doInteract("memowall", "check", emptyMap())
        @Suppress("UNCHECKED_CAST")
        val questions = (result?.get("questions") as? List<String>).orEmpty()
        _dialog.value = DialogUi(
            visible = true, title = label,
            body = if (questions.isEmpty()) "메모벽은 아직 비어 있다." else "",
            sections = if (questions.isNotEmpty()) {
                listOf(ThreadSectionUi("", questions, emptyList(), null, null))
            } else emptyList(),
        )
    }

    // ------------------------------------------------------------------ 기록 화면("기록" 버튼, REC-03)
    fun openRecords(tab: String = "log") {
        val view = recordsView(state)
        val rows = when (tab) {
            "observations" -> view.observations.map { RecordRowUi(it.text) }.ifEmpty { listOf(RecordRowUi("아직 관찰한 게 없다.")) }
            "questions" -> view.questions.map { line ->
                RecordRowUi(line.text, QUESTION_STATE_LABELS.keys.filter { it != line.questionState }.map { st ->
                    val label = when (st) { "arisen" -> "내려놓기"; "held" -> "붙잡기"; else -> "다시 생각하기" }
                    label to {
                        doInteract("records", "question", mapOf("question" to line.questionId, "question_state" to st))
                        openRecords("questions")
                    }
                })
            }.ifEmpty { listOf(RecordRowUi("아직 떠오른 질문이 없다.")) }
            "knowledge" -> view.knowledge.map { RecordRowUi(it.text) }.ifEmpty { listOf(RecordRowUi("아직 알게 된 게 없다.")) }
            "connections" -> view.connections.map { RecordRowUi(it.text) }.ifEmpty { listOf(RecordRowUi("아직 떠오른 연결이 없다.")) }
            else -> state.log.asReversed().map { RecordRowUi(it) }.ifEmpty { listOf(RecordRowUi("아직 기록이 없다.")) }
        }
        _records.value = RecordsUi(tab, rows)
    }

    fun closeRecords() {
        _records.value = null
    }
}
