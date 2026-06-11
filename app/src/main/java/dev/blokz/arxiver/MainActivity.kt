package dev.blokz.arxiver

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dagger.hilt.android.AndroidEntryPoint
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.ui.ArxiverApp
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: dev.blokz.arxiver.data.SettingsRepository

    private var deepLinkPaperId by mutableStateOf<ArxivId?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkPaperId = intent?.extractArxivId()
        val onboarded = runBlocking { settingsRepository.onboarded.first() }
        setContent {
            ArxiverTheme {
                ArxiverApp(
                    deepLinkPaperId = deepLinkPaperId,
                    startOnboarding = !onboarded && deepLinkPaperId == null,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.extractArxivId()?.let { deepLinkPaperId = it }
    }

    /** Handles arxiv.org links (VIEW) and shared text containing them (SEND). */
    private fun Intent.extractArxivId(): ArxivId? {
        data?.toString()?.let { url ->
            ArxivId.parse(url)?.let { (id, _) -> return id }
        }
        if (action == Intent.ACTION_SEND) {
            getStringExtra(Intent.EXTRA_TEXT)
                ?.split(Regex("\\s+"))
                ?.firstNotNullOfOrNull { ArxivId.parse(it)?.first }
                ?.let { return it }
        }
        return null
    }
}
