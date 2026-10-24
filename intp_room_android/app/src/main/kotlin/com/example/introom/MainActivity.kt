package com.example.introom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.introom.ui.GameViewModel
import com.example.introom.ui.IntpRoomTheme
import com.example.introom.ui.RoomScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { IntpRoomApp() }
    }
}

@Composable
private fun IntpRoomApp() {
    IntpRoomTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val viewModel: GameViewModel = viewModel()
            RoomScreen(viewModel = viewModel)
        }
    }
}
