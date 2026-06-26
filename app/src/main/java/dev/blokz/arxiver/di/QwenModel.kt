package dev.blokz.arxiver.di

import javax.inject.Qualifier

/**
 * Distinguishes the Qwen light-tier on-device LLM [dev.blokz.arxiver.core.ml.ModelDownloader]
 * (P-Atlas PA.3) from the [GemmaModel] one and the unqualified BGE embedding one — three singletons
 * of the same type. The Qwen downloader uses a **separate model dir** so `deleteStaleSiblings()`
 * (extension-scoped) can't purge the Gemma `.litertlm` and vice-versa.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class QwenModel
