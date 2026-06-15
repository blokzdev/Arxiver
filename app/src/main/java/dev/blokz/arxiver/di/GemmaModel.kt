package dev.blokz.arxiver.di

import javax.inject.Qualifier

/**
 * Distinguishes the Gemma on-device LLM [dev.blokz.arxiver.core.ml.ModelDownloader]
 * from the unqualified BGE embedding one — two singletons of the same type.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class GemmaModel
