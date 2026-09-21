package com.example.intpgame.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 웹 버전(NES.css) 분위기에 맞춘 종이/잉크 톤. 다이내믹 컬러는 쓰지 않는다.
private val LightColors = lightColorScheme(
    primary = Color(0xFF1B74B8),
    onPrimary = Color.White,
    secondary = Color(0xFF5B6770),
    background = Color(0xFFE9E5D6),
    onBackground = Color(0xFF212529),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF212529),
    surfaceVariant = Color(0xFFE3E0D3),
    onSurfaceVariant = Color(0xFF5B6770),
    outline = Color(0xFF212529),
    error = Color(0xFFD03E26),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF4F2E9),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6CB7F0),
    onPrimary = Color(0xFF07263D),
    secondary = Color(0xFFB4BEC6),
    background = Color(0xFF1B1D1F),
    onBackground = Color(0xFFF1F1F1),
    surface = Color(0xFF26292C),
    onSurface = Color(0xFFF1F1F1),
    surfaceVariant = Color(0xFF3A3F44),
    onSurfaceVariant = Color(0xFFB4BEC6),
    outline = Color(0xFF8A949C),
    error = Color(0xFFE76E55),
    surfaceContainerLowest = Color(0xFF26292C),
    surfaceContainerLow = Color(0xFF26292C),
    surfaceContainer = Color(0xFF26292C),
    surfaceContainerHigh = Color(0xFF2E3236),
    surfaceContainerHighest = Color(0xFF30353A),
)

@Composable
fun IntpTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
