package dev.blokz.arxiver.widget

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.material3.ColorProviders
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dagger.hilt.android.EntryPointAccessors
import dev.blokz.arxiver.MainActivity
import dev.blokz.arxiver.R
import dev.blokz.arxiver.core.database.dao.DigestRow
import dev.blokz.arxiver.core.search.eval.RelevanceThreshold
import dev.blokz.arxiver.ui.theme.DarkColors
import dev.blokz.arxiver.ui.theme.LightColors

/**
 * The home-screen "Likely relevant" widget (P-Ambient PA.2) — surfaces Today's calibrated top-k likely-relevant
 * papers BEFORE app-open, calmly and locally (the count/titles never leave the device). Read-only: it queries the
 * inbox through [WidgetEntryPoint] on each provide, using the SAME relevance cut as the digest + Today
 * ([RelevanceThreshold]). Refreshed for free beside the `EmbeddingWorker` scoring pass (`updateAll`) — no extra
 * wakeups. Placing the widget IS the opt-in, so there is no separate toggle.
 */
class TodayWidget : GlanceAppWidget() {
    override suspend fun provideGlance(
        context: Context,
        id: GlanceId,
    ) {
        val rows = loadTopK(context)
        provideContent {
            GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors)) {
                WidgetBody(rows)
            }
        }
    }

    /** The current top-k (by calibrated score). Fails closed to an empty list — a widget must never crash. */
    private suspend fun loadTopK(context: Context): List<DigestRow> =
        runCatching {
            val entry =
                EntryPointAccessors.fromApplication(context.applicationContext, WidgetEntryPoint::class.java)
            val model = entry.relevanceModelDao().current()
            val cut = RelevanceThreshold.cut(model?.calibrationA, model?.calibrationB)
            entry.inboxDao().activeInboxTopK(cut, MAX_ROWS)
        }.getOrDefault(emptyList())

    companion object {
        private const val MAX_ROWS = 6
    }
}

@Composable
private fun WidgetBody(rows: List<DigestRow>) {
    val context = LocalContext.current
    Column(
        modifier =
            GlanceModifier
                .fillMaxSize()
                .appWidgetBackground()
                .background(GlanceTheme.colors.surfaceVariant)
                .cornerRadius(16.dp)
                .padding(12.dp)
                // Header / empty area opens Today (where the full ranked list lives).
                .clickable(actionStartActivity(openApp(context))),
    ) {
        Text(
            text = context.getString(R.string.widget_title),
            style =
                TextStyle(
                    color = GlanceTheme.colors.primary,
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp,
                ),
            modifier = GlanceModifier.padding(bottom = 8.dp),
        )
        if (rows.isEmpty()) {
            Text(
                text = context.getString(R.string.widget_empty),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp),
            )
        } else {
            LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                items(rows) { row -> PaperRow(row) }
            }
        }
    }
}

@Composable
private fun PaperRow(row: DigestRow) {
    val context = LocalContext.current
    Text(
        text = row.title,
        maxLines = 2,
        style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 14.sp),
        modifier =
            GlanceModifier
                .fillMaxWidth()
                .padding(vertical = 6.dp)
                .clickable(actionStartActivity(openPaper(context, row.paperId))),
    )
}

private fun openApp(context: Context): Intent =
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }

private fun openPaper(
    context: Context,
    storageId: String,
): Intent =
    Intent(context, MainActivity::class.java).apply {
        // A distinct data Uri per row so each row's PendingIntent is unique — identical explicit intents would
        // collide and every row would open the same paper. MainActivity reads the EXTRA, not the data.
        data = "arxiver://widget/paper/${Uri.encode(storageId)}".toUri()
        putExtra(MainActivity.EXTRA_PAPER_STORAGE_ID, storageId)
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
    }
