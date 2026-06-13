package dev.blokz.arxiver

import android.content.Context
import java.io.File
import java.time.Instant

/**
 * Local-only crash visibility for a sideloaded app: the uncaught-exception
 * handler writes the stack trace to app-private storage, and the next launch
 * offers it to the user to copy. Nothing ever leaves the device (red lines:
 * no telemetry).
 */
object CrashReporter {
    private const val FILE_NAME = "last_crash.txt"

    fun install(context: Context) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching {
                File(context.filesDir, FILE_NAME).writeText(
                    buildString {
                        appendLine("Arxiver ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("At: ${Instant.now()}")
                        appendLine("Thread: ${thread.name}")
                        appendLine()
                        append(throwable.stackTraceToString())
                    },
                )
            }
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun pendingCrash(context: Context): String? =
        File(context.filesDir, FILE_NAME)
            .takeIf { it.exists() }
            ?.runCatching { readText() }
            ?.getOrNull()

    fun clear(context: Context) {
        File(context.filesDir, FILE_NAME).delete()
    }
}
