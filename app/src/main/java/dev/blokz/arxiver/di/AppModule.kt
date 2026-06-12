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
    fun libraryDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.LibraryDao = db.libraryDao()

    @Provides
    fun inboxDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.InboxDao = db.inboxDao()

    @Provides
    fun searchDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.SearchDao = db.searchDao()

    @Provides
    @Singleton
    fun localKeywordSearch(
        searchDao: dev.blokz.arxiver.core.database.dao.SearchDao,
    ): dev.blokz.arxiver.core.database.fts.LocalKeywordSearch =
        dev.blokz.arxiver.core.database.fts.LocalKeywordSearch(searchDao)

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
    fun routineDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.RoutineDao = db.routineDao()

    @Provides
    @Singleton
    fun tokenVault(
        @ApplicationContext context: Context,
    ): dev.blokz.arxiver.core.claude.TokenVault = dev.blokz.arxiver.core.claude.TokenVault(context)

    @Provides
    @Singleton
    fun payloadBuilder(): dev.blokz.arxiver.core.claude.PayloadBuilder =
        dev.blokz.arxiver.core.claude.PayloadBuilder(appVersion = dev.blokz.arxiver.BuildConfig.VERSION_NAME)

    @Provides
    @Singleton
    fun routineTriggerClient(
        httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.claude.RoutineTriggerClient =
        dev.blokz.arxiver.core.claude.RoutineTriggerClient(httpClient, dispatchers)

    @Provides
    @Singleton
    fun backupManager(
        libraryExporter: dev.blokz.arxiver.data.LibraryExporter,
        paperDao: PaperDao,
        libraryDao: dev.blokz.arxiver.core.database.dao.LibraryDao,
        followDao: FollowDao,
        routineDao: dev.blokz.arxiver.core.database.dao.RoutineDao,
        dispatchRepository: dagger.Lazy<dev.blokz.arxiver.data.DispatchRepository>,
    ): dev.blokz.arxiver.data.BackupManager =
        dev.blokz.arxiver.data.BackupManager(
            libraryExporter = libraryExporter,
            paperDao = paperDao,
            libraryDao = libraryDao,
            followDao = followDao,
            routineDao = routineDao,
            routineRestorer = { name, url -> dispatchRepository.get().addRoutine(name, url, token = "") },
        )

    @Provides
    fun routineSetupGateway(
        repository: dev.blokz.arxiver.data.DispatchRepository,
    ): dev.blokz.arxiver.data.RoutineSetupGateway = repository

    @Provides
    fun citationDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.CitationDao = db.citationDao()

    @Provides
    @Singleton
    fun semanticScholarClient(
        httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.network.s2.SemanticScholarClient =
        dev.blokz.arxiver.core.network.s2.SemanticScholarClient(httpClient, dispatchers)

    @Provides
    fun embeddingDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.EmbeddingDao = db.embeddingDao()

    @Provides
    @Singleton
    fun modelDownloader(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.ml.ModelDownloader =
        dev.blokz.arxiver.core.ml.ModelDownloader(
            httpClient = httpClient,
            dispatchers = dispatchers,
            modelDir = java.io.File(context.filesDir, "models"),
        )

    @Provides
    @Singleton
    fun embeddingService(
        @ApplicationContext context: Context,
        modelDownloader: dev.blokz.arxiver.core.ml.ModelDownloader,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.ml.EmbeddingService =
        dev.blokz.arxiver.core.ml.EmbeddingService(
            modelDownloader = modelDownloader,
            tokenizerProvider = {
                dev.blokz.arxiver.core.ml.WordPieceTokenizer(context.assets.open("bge_vocab.txt"))
            },
            dispatchers = dispatchers,
        )

    @Provides
    @Singleton
    fun vectorIndex(
        embeddingDao: dev.blokz.arxiver.core.database.dao.EmbeddingDao,
    ): dev.blokz.arxiver.core.search.VectorIndex = dev.blokz.arxiver.core.search.VectorIndex(embeddingDao)

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
