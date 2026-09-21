package com.example.intpgame.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.intpgame.engine.ActionView
import com.example.intpgame.engine.EventPayload
import com.example.intpgame.engine.GameSnapshot
import com.example.intpgame.engine.ObjectView

private val Ink = Color(0xFF212529)
private val Gold = Color(0xFFF7D51D)
private val Good = Color(0xFF2E9D45)
private val Warn = Color(0xFFE0A100)
private val Bad = Color(0xFFD03E26)
private val Calm = Color(0xFF1B74B8)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(vm: GameViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snap = ui.snapshot
    val snackbar = remember { SnackbarHostState() }
    var askReset by remember { mutableStateOf(false) }

    // 규칙 위반 메시지(기력 부족 등)를 스낵바로 한 번만 보여준다.
    LaunchedEffect(ui.messageSeq) {
        val text = ui.message
        if (text != null) {
            snackbar.showSnackbar(text)
            vm.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("INTP 키우기", fontWeight = FontWeight.Bold) },
                actions = { TextButton(onClick = { askReset = true }) { Text("새로 시작") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { StatusCard(snap) }
            item { LatestLogCard(snap.logs.lastOrNull().orEmpty()) }

            if (snap.locations.size > 1) {
                item {
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        snap.locations.forEach { loc ->
                            FilterChip(
                                selected = loc.id == snap.locationId,
                                onClick = { vm.move(loc.id) },
                                label = { Text(loc.name) },
                                enabled = snap.activeEvent == null,
                            )
                        }
                    }
                }
            }

            item {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(snap.locationName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        snap.locationDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            items(snap.objects, key = { it.id }) { obj ->
                ObjectCard(obj, onAction = { id -> vm.doAction(id) })
            }

            item { HistoryCard(snap.logs) }
        }
    }

    // 이벤트는 반드시 선택해야 하므로 바깥 터치/뒤로가기로 닫히지 않는다.
    snap.activeEvent?.let { event ->
        EventDialog(event, onChoose = { idx -> vm.chooseEvent(event.id, idx) })
    }

    if (askReset) {
        AlertDialog(
            onDismissRequest = { askReset = false },
            title = { Text("처음부터 다시 시작할까요?") },
            text = { Text("지금까지의 진행은 사라집니다.") },
            confirmButton = {
                TextButton(onClick = { askReset = false; vm.reset() }) { Text("새로 시작") }
            },
            dismissButton = {
                TextButton(onClick = { askReset = false }) { Text("취소") }
            },
        )
    }
}

// ---------------------------------------------------------------- 상태
@Composable
private fun StatusCard(snap: GameSnapshot) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Day ${snap.day}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(snap.periodLabel, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }

            val energyColor = when {
                snap.energy > 50 -> Good
                snap.energy > 20 -> Warn
                else -> Bad
            }
            val focusColor = when {
                snap.hyperfocus >= 80 -> Bad
                snap.hyperfocus >= 50 -> Warn
                else -> Calm
            }
            Meter("기력", snap.energy, energyColor)
            Meter("과몰입", snap.hyperfocus, focusColor)

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("돈  ${"%,d".format(snap.money)}")
                Text("식량  ${snap.food}")
            }

            HorizontalDivider()

            snap.stats.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    pair.forEach { s ->
                        Row(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(s.label, style = MaterialTheme.typography.bodyMedium)
                            Text(s.value.toString(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (pair.size < 2) Spacer(modifier = Modifier.weight(1f))
                }
            }

            if (snap.interests.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    "관심사  " + snap.interests.joinToString("  ") { "${it.label} ${it.value}" },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (snap.discoveredInfo.isNotEmpty()) {
                Text(
                    "발견  " + snap.discoveredInfo.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Meter(label: String, value: Int, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Text("$value/100", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { value / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

// ---------------------------------------------------------------- 오브젝트 / 행동
@Composable
private fun ObjectCard(obj: ObjectView, onAction: (String) -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(obj.icon, color = Gold, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Column {
                    Text(obj.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        obj.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            obj.actions.forEach { action ->
                ActionButton(action, onClick = { onAction(action.id) })
            }
        }
    }
}

@Composable
private fun ActionButton(action: ActionView, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = action.enabled,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(6.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(action.label, fontWeight = FontWeight.Bold)
            Text(
                text = if (action.enabled) action.cost.ifEmpty { action.hint } else action.reason.ifEmpty { "지금은 할 수 없어" },
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

// ---------------------------------------------------------------- 기록
@Composable
private fun LatestLogCard(text: String) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("방금", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HistoryCard(logs: List<String>) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("기록", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            // 최근 것이 위로 오도록 뒤집어 보여주고, 너무 길어지지 않게 30줄만 쓴다.
            logs.takeLast(30).asReversed().forEach { line ->
                val isDayBreak = line.contains("── Day")
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isDayBreak) Warn else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 이벤트
@Composable
private fun EventDialog(event: EventPayload, onChoose: (Int) -> Unit) {
    AlertDialog(
        onDismissRequest = { /* 선택 전에는 닫히지 않는다 */ },
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(event.title, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(event.description, style = MaterialTheme.typography.bodyMedium)
                event.choices.forEachIndexed { index, choice ->
                    Button(
                        onClick = { onChoose(index) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(6.dp),
                    ) {
                        Text("${index + 1}. $choice")
                    }
                }
            }
        },
        confirmButton = {},
    )
}
