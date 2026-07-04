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
import dev.blokz.arxiver.core.network.AllowedHostsInterceptor
import dev.blokz.arxiver.core.network.arxiv.ArxivApiClient
import dev.blokz.arxiver.core.network.arxiv.ArxivRateLimiter
import kotlinx.coroutines.flow.first
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
    fun pageImageSource(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.data.PageImageSource = dev.blokz.arxiver.data.PdfPageImageSource(context, dispatchers)

    @Provides
    @Singleton
    fun relationGraphSource(
        impl: dev.blokz.arxiver.data.RelationGraphRepository,
    ): dev.blokz.arxiver.data.RelationGraphSource = impl

    @Provides
    @Singleton
    fun collectionGraphSource(
        impl: dev.blokz.arxiver.data.CollectionGraphRepository,
    ): dev.blokz.arxiver.data.CollectionGraphSource = impl

    @Provides
    @Singleton
    fun readAloud(impl: dev.blokz.arxiver.tts.TextToSpeechManager): dev.blokz.arxiver.tts.ReadAloud = impl

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

    /**
     * The dedicated egress-gated client for the arXiv fetch group (Atom + PDF + future HTML/image).
     * Reuses the bare client's connection pool/config via [okHttpClient]'s [OkHttpClient.newBuilder]
     * and adds the [AllowedHostsInterceptor] as a NETWORK interceptor (fires per redirect hop). The AI
     * providers / routine trigger / model downloaders stay on the bare unqualified client — the AI-key
     * path is never routed through the host gate (P-HTML PH.2).
     */
    @Provides
    @Singleton
    @ArxivClient
    fun arxivHttpClient(httpClient: OkHttpClient): OkHttpClient {
        val gate = AllowedHostsInterceptor()
        return httpClient.newBuilder()
            .addInterceptor(gate) // pre-connection: a disallowed original host opens no socket
            .addNetworkInterceptor(gate) // per redirect hop: a disallowed 3xx target is blocked
            .build()
    }

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
    fun aiKeyVault(
        @ApplicationContext context: Context,
    ): dev.blokz.arxiver.core.ai.AiKeyVault = dev.blokz.arxiver.core.ai.AiKeyVault(context)

    @Provides
    fun aiKeyStore(vault: dev.blokz.arxiver.core.ai.AiKeyVault): dev.blokz.arxiver.core.ai.AiKeyStore = vault

    @Provides
    @Singleton
    fun anthropicProvider(
        httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
        aiKeyVault: dev.blokz.arxiver.core.ai.AiKeyVault,
    ): dev.blokz.arxiver.core.ai.AnthropicProvider =
        dev.blokz.arxiver.core.ai.AnthropicProvider(
            httpClient = httpClient,
            dispatchers = dispatchers,
            apiKey = { aiKeyVault.get(dev.blokz.arxiver.core.ai.ProviderId.CLAUDE) },
        )

    @Provides
    @Singleton
    fun geminiProvider(
        httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
        aiKeyVault: dev.blokz.arxiver.core.ai.AiKeyVault,
    ): dev.blokz.arxiver.core.ai.GeminiProvider =
        dev.blokz.arxiver.core.ai.GeminiProvider(
            httpClient = httpClient,
            dispatchers = dispatchers,
            apiKey = { aiKeyVault.get(dev.blokz.arxiver.core.ai.ProviderId.GEMINI) },
        )

    @Provides
    @Singleton
    @GemmaModel
    fun gemmaModelDownloader(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.ml.ModelDownloader =
        dev.blokz.arxiver.core.ml.ModelDownloader(
            httpClient = httpClient,
            dispatchers = dispatchers,
            modelDir = java.io.File(context.filesDir, "models"),
            spec = dev.blokz.arxiver.core.ai.GemmaEngine.SPEC,
        )

    @Provides
    @Singleton
    fun gemmaEngine(
        @GemmaModel gemmaDownloader: dev.blokz.arxiver.core.ml.ModelDownloader,
        dispatchers: DispatcherProvider,
        @ApplicationContext context: Context,
    ): dev.blokz.arxiver.core.ai.GemmaEngine =
        dev.blokz.arxiver.core.ai.GemmaEngine(
            modelDownloader = gemmaDownloader,
            dispatchers = dispatchers,
            cacheDir = context.cacheDir,
        )

    @Provides
    @Singleton
    @QwenModel
    fun qwenModelDownloader(
        @ApplicationContext context: Context,
        httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.ml.ModelDownloader =
        dev.blokz.arxiver.core.ml.ModelDownloader(
            httpClient = httpClient,
            dispatchers = dispatchers,
            // Separate dir from Gemma: deleteStaleSiblings() is extension-scoped (.litertlm),
            // so co-locating the two LLM files would have them purge each other (P-Atlas PA.3).
            modelDir = java.io.File(context.filesDir, "models/light"),
            spec = dev.blokz.arxiver.core.ai.QwenEngine.SPEC,
        )

    @Provides
    @Singleton
    fun qwenEngine(
        @QwenModel qwenDownloader: dev.blokz.arxiver.core.ml.ModelDownloader,
        dispatchers: DispatcherProvider,
        @ApplicationContext context: Context,
    ): dev.blokz.arxiver.core.ai.QwenEngine =
        dev.blokz.arxiver.core.ai.QwenEngine(
            modelDownloader = qwenDownloader,
            dispatchers = dispatchers,
            cacheDir = context.cacheDir,
        )

    @Provides
    @Singleton
    fun nanoAvailability(): dev.blokz.arxiver.core.ai.NanoAvailability =
        dev.blokz.arxiver.core.ai.MlKitNanoAvailability()

    @Provides
    @Singleton
    fun nanoEngine(
        nanoAvailability: dev.blokz.arxiver.core.ai.NanoAvailability,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.ai.NanoEngine = dev.blokz.arxiver.core.ai.NanoEngine(nanoAvailability, dispatchers)

    @Provides
    @Singleton
    fun onDeviceProvider(
        gemmaEngine: dev.blokz.arxiver.core.ai.GemmaEngine,
        qwenEngine: dev.blokz.arxiver.core.ai.QwenEngine,
        nanoEngine: dev.blokz.arxiver.core.ai.NanoEngine,
        dispatchers: DispatcherProvider,
        aiProviderStore: dev.blokz.arxiver.data.AiProviderStore,
    ): dev.blokz.arxiver.core.ai.OnDeviceProvider =
        // Default order best-first: Gemma (most capable) → Qwen light tier → Nano (zero-download).
        // pickReadyEngine filters by isReady() then honours the user's preferredTier (P-Atlas PA.3).
        dev.blokz.arxiver.core.ai.OnDeviceProvider(
            engines = listOf(gemmaEngine, qwenEngine, nanoEngine),
            dispatchers = dispatchers,
            preferredTier = { aiProviderStore.preferredOnDeviceTier.first() },
        )

    @Provides
    @Singleton
    fun deviceCapabilityProbe(
        @ApplicationContext context: Context,
        nanoAvailability: dev.blokz.arxiver.core.ai.NanoAvailability,
        @GemmaModel gemmaDownloader: dev.blokz.arxiver.core.ml.ModelDownloader,
        @QwenModel lightDownloader: dev.blokz.arxiver.core.ml.ModelDownloader,
        aiKeyStore: dev.blokz.arxiver.core.ai.AiKeyStore,
    ): dev.blokz.arxiver.core.ai.DeviceCapabilityProbe =
        dev.blokz.arxiver.core.ai.AndroidDeviceCapabilityProbe(
            context = context,
            nanoAvailability = nanoAvailability,
            gemmaDownloader = gemmaDownloader,
            lightDownloader = lightDownloader,
            keyStore = aiKeyStore,
        )

    @Provides
    @Singleton
    @GemmaModel
    fun gemmaModelController(
        @GemmaModel gemmaDownloader: dev.blokz.arxiver.core.ml.ModelDownloader,
        syncScheduler: dev.blokz.arxiver.sync.SyncScheduler,
    ): dev.blokz.arxiver.data.OnDeviceModelController =
        dev.blokz.arxiver.data.DefaultOnDeviceModelController(gemmaDownloader) {
            syncScheduler.downloadOnDeviceModel()
        }

    @Provides
    @Singleton
    @QwenModel
    fun lightModelController(
        @QwenModel lightDownloader: dev.blokz.arxiver.core.ml.ModelDownloader,
        syncScheduler: dev.blokz.arxiver.sync.SyncScheduler,
    ): dev.blokz.arxiver.data.OnDeviceModelController =
        dev.blokz.arxiver.data.DefaultOnDeviceModelController(lightDownloader) {
            syncScheduler.downloadLightModel()
        }

    @Provides
    @Singleton
    fun providerRegistry(
        anthropic: dev.blokz.arxiver.core.ai.AnthropicProvider,
        gemini: dev.blokz.arxiver.core.ai.GeminiProvider,
        onDevice: dev.blokz.arxiver.core.ai.OnDeviceProvider,
        aiKeyStore: dev.blokz.arxiver.core.ai.AiKeyStore,
    ): dev.blokz.arxiver.core.ai.ProviderRegistry =
        dev.blokz.arxiver.core.ai.ProviderRegistry(listOf(anthropic, gemini, onDevice), aiKeyStore)

    @Provides
    fun aiProviderStore(settings: dev.blokz.arxiver.data.SettingsRepository): dev.blokz.arxiver.data.AiProviderStore =
        settings

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
    fun chunkEmbeddingDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao =
        db.chunkEmbeddingDao()

    @Provides
    @Singleton
    fun ragRetriever(
        chunkEmbeddingDao: dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao,
    ): dev.blokz.arxiver.core.search.RagRetriever =
        dev.blokz.arxiver.core.search.RagRetriever(
            vectorSource = dev.blokz.arxiver.core.search.DaoChunkVectorSource(chunkEmbeddingDao),
            keywordSource = dev.blokz.arxiver.core.search.DaoChunkKeywordSource(chunkEmbeddingDao),
        )

    @Provides
    @Singleton
    fun ragIndexer(
        paperDao: PaperDao,
        libraryDao: dev.blokz.arxiver.core.database.dao.LibraryDao,
        chunkEmbeddingDao: dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao,
        embeddingService: dev.blokz.arxiver.core.ml.EmbeddingService,
    ): dev.blokz.arxiver.rag.RagIndexer =
        dev.blokz.arxiver.rag.RagIndexer(
            paperDao = paperDao,
            libraryDao = libraryDao,
            chunkDao = chunkEmbeddingDao,
            chunker = dev.blokz.arxiver.core.search.TextChunker(),
            modelName = dev.blokz.arxiver.sync.EmbeddingWorker.MODEL_NAME,
            embed = embeddingService::embedPassages,
        )

    @Provides
    fun chatDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.ChatDao = db.chatDao()

    @Provides
    @Singleton
    fun scopeIndexer(ragIndexer: dev.blokz.arxiver.rag.RagIndexer): dev.blokz.arxiver.rag.ScopeIndexer =
        dev.blokz.arxiver.rag.ScopeIndexer { scope ->
            when (scope) {
                is dev.blokz.arxiver.core.search.RetrievalScope.Collection ->
                    ragIndexer.indexCollection(scope.collectionId)
                // Index the paper's abstract on open so per-paper Ask is grounded even for
                // inbox papers the library backfill never reaches (the sheet promises
                // "grounded in its abstract"). Already-embedded papers no-op via the model guard.
                is dev.blokz.arxiver.core.search.RetrievalScope.Paper ->
                    ragIndexer.indexPaper(scope.paperId)
            }
        }

    @Provides
    @Singleton
    fun providerResolver(
        registry: dev.blokz.arxiver.core.ai.ProviderRegistry,
        store: dev.blokz.arxiver.data.AiProviderStore,
        onDeviceProvider: dev.blokz.arxiver.core.ai.OnDeviceProvider,
    ): dev.blokz.arxiver.core.ai.ProviderResolver =
        dev.blokz.arxiver.core.ai.ProviderResolver(
            registry = registry,
            selected = { store.selectedAiProvider.first() },
            preferOnDevice = { store.preferOnDeviceWhenReady.first() },
            // Delegate to the SAME engines list chat() serves from — never hand-enumerate engines
            // here (a stale `gemma || nano` left Qwen-only devices at "not configured"; PA.3 hotfix).
            onDeviceReady = { onDeviceProvider.isReady() },
        )

    @Provides
    @Singleton
    fun chatRepository(
        chatDao: dev.blokz.arxiver.core.database.dao.ChatDao,
        ragRetriever: dev.blokz.arxiver.core.search.RagRetriever,
        providerResolver: dev.blokz.arxiver.core.ai.ProviderResolver,
        embeddingService: dev.blokz.arxiver.core.ml.EmbeddingService,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.data.ChatRepository =
        dev.blokz.arxiver.data.ChatRepository(
            chatDao = chatDao,
            ragRetriever = ragRetriever,
            providerResolver = providerResolver,
            assembler = dev.blokz.arxiver.chat.ChatContextAssembler(),
            previewBuilder = dev.blokz.arxiver.chat.ChatPreviewBuilder(),
            embedQuery = embeddingService::embedQuery,
            dispatchers = dispatchers,
        )

    @Provides
    @Singleton
    fun pdfDownloader(
        @ArxivClient httpClient: OkHttpClient,
        rateLimiter: ArxivRateLimiter,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.network.pdf.PdfDownloader =
        dev.blokz.arxiver.core.network.pdf.PdfDownloader(httpClient, rateLimiter, dispatchers)

    @Provides
    @Singleton
    fun arxivApiClient(
        @ArxivClient httpClient: OkHttpClient,
        rateLimiter: ArxivRateLimiter,
        dispatchers: DispatcherProvider,
    ): ArxivApiClient = ArxivApiClient(httpClient, rateLimiter, dispatchers)

    @Provides
    @Singleton
    fun htmlFetcher(
        @ArxivClient httpClient: OkHttpClient,
        rateLimiter: ArxivRateLimiter,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.ai.HtmlFetcher =
        dev.blokz.arxiver.core.ai.HtmlFetcher(httpClient, rateLimiter, dispatchers)

    @Provides
    @Singleton
    fun htmlImageFetcher(
        @ArxivClient httpClient: OkHttpClient,
        rateLimiter: ArxivRateLimiter,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.ai.HtmlImageFetcher =
        dev.blokz.arxiver.core.ai.HtmlImageFetcher(httpClient, rateLimiter, dispatchers)

    @Provides
    @Singleton
    fun htmlStorage(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.ai.HtmlStorage = dev.blokz.arxiver.core.ai.HtmlStorage(context.filesDir, dispatchers)
}
