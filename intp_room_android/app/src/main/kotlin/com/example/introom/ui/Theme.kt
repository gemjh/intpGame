package com.example.introom.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 웹 버전(game-interact.css)의 어두운 밤바다 톤 패널을 그대로 가져왔다 — 라이트 테마는 따로 안 만든다
// (이 게임은 밤 장면이 핵심이라 처음부터 다크 테마 하나로 통일되어 있었다).
private val Colors = darkColorScheme(
    primary = Color(0xFF6CB7F0),
    onPrimary = Color(0xFF07263D),
    secondary = Color(0xFFFFD28A),
    background = Color(0xFF080D19),
    onBackground = Color(0xFFDFE6F3),
    surface = Color(0xFF202E45),
    onSurface = Color(0xFFDFE6F3),
    surfaceVariant = Color(0xFF1A2438),
    onSurfaceVariant = Color(0xFF9FB0CC),
    outline = Color(0xFFAAB9CF),
    error = Color(0xFFE76E55),
)

@Composable
fun IntpRoomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Colors, content = content)
}
