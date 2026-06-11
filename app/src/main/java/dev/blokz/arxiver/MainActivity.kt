package dev.blokz.arxiver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import dagger.hilt.android.AndroidEntryPoint
import dev.blokz.arxiver.ui.ArxiverApp
import dev.blokz.arxiver.ui.theme.ArxiverTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ArxiverTheme {
                ArxiverApp()
            }
        }
    }
}
