package dev.blokz.arxiver.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.blokz.arxiver.core.common.DefaultDispatcherProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.TaxonomySeeder
import dev.blokz.arxiver.core.database.dao.CategoryDao
import dev.blokz.arxiver.core.database.dao.FollowDao
import dev.blokz.arxiver.core.database.dao.PaperDao
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides
    @Singleton
    fun dispatcherProvider(): DispatcherProvider = DefaultDispatcherProvider()

    @Provides
    @Singleton
    fun database(
        @ApplicationContext context: Context,
    ): ArxiverDatabase = ArxiverDatabase.build(context)

    @Provides
    fun paperDao(db: ArxiverDatabase): PaperDao = db.paperDao()

    @Provides
    fun categoryDao(db: ArxiverDatabase): CategoryDao = db.categoryDao()

    @Provides
    fun followDao(db: ArxiverDatabase): FollowDao = db.followDao()

    @Provides
    @Singleton
    fun taxonomySeeder(categoryDao: CategoryDao): TaxonomySeeder = TaxonomySeeder(categoryDao)

    @Provides
    @Singleton
    fun okHttpClient(): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()

    /** Single instance app-wide — the arXiv rate-limit red line depends on it. */
    @Provides
    @Singleton
    fun arxivRateLimiter(): ArxivRateLimiter = ArxivRateLimiter()

    @Provides
    @Singleton
    fun pdfDownloader(
        httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.network.pdf.PdfDownloader =
        dev.blokz.arxiver.core.network.pdf.PdfDownloader(httpClient, dispatchers)

    @Provides
    @Singleton
    fun arxivApiClient(
        httpClient: OkHttpClient,
        rateLimiter: ArxivRateLimiter,
        dispatchers: DispatcherProvider,
    ): ArxivApiClient = ArxivApiClient(httpClient, rateLimiter, dispatchers)
}
