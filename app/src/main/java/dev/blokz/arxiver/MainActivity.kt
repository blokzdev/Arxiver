package dev.blokz.arxiver

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
        // Must precede super.onCreate — hands the system splash to Compose.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkPaperId = intent?.extractArxivId()
        val onboarded = runBlocking { settingsRepository.onboarded.first() }
        val pendingCrash = CrashReporter.pendingCrash(this)
        setContent {
            ArxiverTheme {
                var crashTrace by remember { mutableStateOf(pendingCrash) }
                ArxiverApp(
                    deepLinkPaperId = deepLinkPaperId,
                    startOnboarding = !onboarded && deepLinkPaperId == null,
                )
                crashTrace?.let { trace ->
                    CrashDialog(
                        trace = trace,
                        onDismiss = {
                            CrashReporter.clear(this)
                            crashTrace = null
                        },
                    )
                }
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

/** Offers the previous run's stack trace for copying — local-only. */
@Composable
private fun CrashDialog(
    trace: String,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.crash_dialog_title)) },
        text = {
            Column {
                Text(
                    stringResource(R.string.crash_dialog_body),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = trace,
                    style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                    modifier =
                        Modifier
                            .padding(top = 8.dp)
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("Arxiver crash trace", trace))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.crash_copy)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.crash_dismiss)) }
        },
    )
}
