package dev.blokz.arxiver.di

import javax.inject.Qualifier

/**
 * The dedicated, egress-gated [okhttp3.OkHttpClient] for the **arXiv fetch group only** (Atom API,
 * PDFs, and the future P-HTML HTML/image fetchers). It carries the
 * [dev.blokz.arxiver.core.network.AllowedHostsInterceptor]; spacing rides the shared `ArxivRateLimiter`
 * at the fetcher seam. The bare unqualified client (AI providers, routine trigger, model downloads)
 * deliberately does **not** get this — the AI-key path stays uncoupled from the rate limiter (P-HTML PH.2).
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ArxivClient
