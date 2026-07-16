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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.ui.ArxiverApp
import dev.blokz.arxiver.ui.theme.ArxiverTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var settingsRepository: dev.blokz.arxiver.data.SettingsRepository

    @Inject lateinit var dispatchers: DispatcherProvider

    private var deepLinkPaperId by mutableStateOf<ArxivId?>(null)

    // The PA.2 widget deep-links to a paper by its opaque storageId (works for any origin, unlike the arXiv-only
    // VIEW intent). Read from the launch/new intent's extra and navigated in [ArxiverApp].
    private var deepLinkStorageId by mutableStateOf<String?>(null)

    // null = not yet read. Formerly a runBlocking DataStore read that blocked the first frame (P-Prove PP.4); now
    // resolved off the main thread with the system splash held until it lands.
    private var onboarded by mutableStateOf<Boolean?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must precede super.onCreate — hands the system splash to Compose.
        val splashScreen = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        deepLinkPaperId = intent?.extractArxivId()
        deepLinkStorageId = intent?.getStringExtra(EXTRA_PAPER_STORAGE_ID)
        // Hold the splash while `onboarded` resolves off the main thread. TTID barely moves (the splash is simply
        // held ~as long as the read took to block before), but the main thread is now free to do the rest of
        // startup in parallel — the real TTFD / unblocked-main win a Baseline Profile can't give (P-Prove PP.4).
        splashScreen.setKeepOnScreenCondition { onboarded == null }
        lifecycleScope.launch { onboarded = settingsRepository.onboarded.first() }
        setContent {
            ArxiverTheme {
                // NavHost's startDestination is fixed at first composition, so the app must not compose under a
                // not-yet-known `onboarded` — the splash covers this brief gap. `deepLinkPaperId` is set
                // synchronously above, so the fork is correct the moment `onboarded` lands.
                onboarded?.let { resolved ->
                    ArxiverApp(
                        deepLinkPaperId = deepLinkPaperId,
                        deepLinkStorageId = deepLinkStorageId,
                        startOnboarding = shouldStartOnboarding(resolved, deepLinkPaperId, deepLinkStorageId),
                    )
                }
                // CrashReporter.pendingCrash reads filesDir — deferred off the first-frame path to a post-composition
                // effect (was a synchronous onCreate read). Still shows after a real crash (P-Prove PP.4).
                var crashTrace by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    crashTrace = withContext(dispatchers.io) { CrashReporter.pendingCrash(this@MainActivity) }
                }
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
        intent.getStringExtra(EXTRA_PAPER_STORAGE_ID)?.let { deepLinkStorageId = it }
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

    companion object {
        /** Opaque `papers.id` storageId the PA.2 widget passes to deep-link into a specific paper (any origin). */
        const val EXTRA_PAPER_STORAGE_ID = "dev.blokz.arxiver.extra.PAPER_STORAGE_ID"
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
