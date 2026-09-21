package com.example.intpgame

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.example.intpgame.ui.GameScreen
import com.example.intpgame.ui.GameViewModel
import com.example.intpgame.ui.IntpTheme

class MainActivity : ComponentActivity() {

    private val viewModel: GameViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IntpTheme {
                GameScreen(viewModel)
            }
        }
    }
}
