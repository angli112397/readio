package com.example.readio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.readio.ui.navigation.ReadioNavGraph
import com.example.readio.ui.theme.ReadioTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ReadioApp()
        }
    }
}

@Composable
private fun ReadioApp(viewModel: AppViewModel = hiltViewModel()) {
    val prefs by viewModel.readingPrefs.collectAsStateWithLifecycle()
    ReadioTheme(readingTheme = prefs.readingTheme) {
        ReadioNavGraph()
    }
}
