package dev.blokz.arxiver.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** The `AppWidgetProvider` the system instantiates for the "Likely relevant" widget (P-Ambient PA.2). */
class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TodayWidget()
}
