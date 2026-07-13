package dev.blokz.arxiver.di

import android.content.Context
import androidx.room.withTransaction
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.blokz.arxiver.core.common.DefaultDispatcherProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.common.getOrNull
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
    // OpenAlex "polite pool" contact (P-Feeds). A real contact is etiquette (like the arXiv UA contact — see
    // HUMAN.md §2); OpenAlex does not verify it. Swap for the Co-Founder's preferred contact when provided.
    private const val OPENALEX_MAILTO = "arxiver@blokz.dev"

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
    fun relevanceModelDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.RelevanceModelDao =
        db.relevanceModelDao()

    @Provides
    fun paperFeedbackDao(db: ArxiverDatabase): dev.blokz.arxiver.core.database.dao.PaperFeedbackDao =
        db.paperFeedbackDao()

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
    fun testCorpusSeeder(
        db: ArxiverDatabase,
        paperDao: PaperDao,
        embeddingDao: dev.blokz.arxiver.core.database.dao.EmbeddingDao,
        followDao: FollowDao,
        inboxDao: dev.blokz.arxiver.core.database.dao.InboxDao,
    ): dev.blokz.arxiver.bench.TestCorpusSeeder =
        dev.blokz.arxiver.bench.TestCorpusSeeder(
            paperDao = paperDao,
            embeddingDao = embeddingDao,
            followDao = followDao,
            inboxDao = inboxDao,
            transaction = { block -> db.withTransaction { block() } },
        )

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
    fun digestNotifier(impl: dev.blokz.arxiver.sync.AndroidDigestNotifier): dev.blokz.arxiver.sync.DigestNotifier = impl

    @Provides
    @Singleton
    fun applicationScope(
        dispatchers: dev.blokz.arxiver.core.common.DispatcherProvider,
    ): kotlinx.coroutines.CoroutineScope =
        // Outlives any ViewModel — used for work that must survive scope cancellation (e.g. the
        // reader's final reading-position flush in onCleared; P-HTML PH.6). SupervisorJob so one
        // failed job never kills the scope.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + dispatchers.io)

    // P-Tools PT.3: S2 now runs on the @ArxivClient host-gated client (egress allowlisted to
    // api.semanticscholar.org — the previously-aspirational gate now fires). The 1.2s mutex is internal
    // to the client, so gating adds no ≥3s throttle. apiKey is a per-request supplier reading the
    // optional BYOK key from the vault (absent → free tier); a key entered after this singleton builds
    // is still honored (mirrors anthropicProvider).
    @Provides
    @Singleton
    fun semanticScholarClient(
        @ArxivClient httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
        aiKeyVault: dev.blokz.arxiver.core.ai.AiKeyVault,
    ): dev.blokz.arxiver.core.network.s2.SemanticScholarClient =
        dev.blokz.arxiver.core.network.s2.SemanticScholarClient(
            httpClient,
            dispatchers,
            apiKey = { aiKeyVault.get(dev.blokz.arxiver.core.ai.ProviderId.SEMANTIC_SCHOLAR) },
        )

    // P-Tools PT.4: chemRxiv (Cambridge Open Engage) search client. **UNWIRED since P-Feeds PF.1** — chemRxiv's
    // API is Cloudflare-dead for scripted clients (memory chemrxiv-cloudflare-blocked), so `search_chemrxiv`
    // now discovers via OpenAlex. Kept in-tree (+ its test) in case the API ever un-gates; this provider has no
    // consumer today (Hilt never builds an unused @Provides).
    @Provides
    @Singleton
    fun chemRxivClient(
        @ArxivClient httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.network.chemrxiv.ChemRxivClient =
        dev.blokz.arxiver.core.network.chemrxiv.ChemRxivClient(httpClient, dispatchers)

    // P-Feeds PF.0: OpenAlex discovery client on the @ArxivClient host-gated client (egress allowlisted to
    // api.openalex.org). Its own 1.2s polite mutex — NOT the ≥3s arXiv limiter. Free "polite pool" via the
    // OPENALEX_MAILTO param; an optional BYOK key (absent → free tier) upgrades to the prepaid tier and is
    // honored even if entered after this singleton builds (per-request supplier, mirrors semanticScholarClient).
    @Provides
    @Singleton
    fun openAlexClient(
        @ArxivClient httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
        aiKeyVault: dev.blokz.arxiver.core.ai.AiKeyVault,
    ): dev.blokz.arxiver.core.network.openalex.OpenAlexClient =
        dev.blokz.arxiver.core.network.openalex.OpenAlexClient(
            httpClient,
            dispatchers,
            mailto = OPENALEX_MAILTO,
            apiKey = { aiKeyVault.get(dev.blokz.arxiver.core.ai.ProviderId.OPENALEX) },
        )

    // P-Feeds PF.2: native bioRxiv/medRxiv discovery client (api.biorxiv.org, un-gated, server-side category).
    // Own 1.2s polite mutex — NOT the ≥3s arXiv limiter. Serves the follow engine, not a chat tool.
    @Provides
    @Singleton
    fun bioRxivApiClient(
        @ArxivClient httpClient: OkHttpClient,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.network.biorxiv.BioRxivApiClient =
        dev.blokz.arxiver.core.network.biorxiv.BioRxivApiClient(httpClient, dispatchers)

    // P-Feeds PF.2: the multi-source follow engine. Each source resolves to its best backend — bio/med native,
    // chemRxiv (+ new sources, PF.3) via OpenAlex. arXiv keeps its native Atom path (backendFor → null).
    @Provides
    @Singleton
    fun preprintBackendRegistry(
        bioRxivApiClient: dev.blokz.arxiver.core.network.biorxiv.BioRxivApiClient,
        openAlexClient: dev.blokz.arxiver.core.network.openalex.OpenAlexClient,
    ): dev.blokz.arxiver.core.network.PreprintBackendRegistry =
        dev.blokz.arxiver.core.network.PreprintBackendRegistry(
            bioRxivBackend = dev.blokz.arxiver.core.network.biorxiv.BioRxivBackend(bioRxivApiClient),
            // The Source→SID mapping lives with the SID_* constants (exhaustive, no silent else→null).
            openAlexBackend =
                dev.blokz.arxiver.core.network.openalex.OpenAlexBackend(
                    openAlexClient,
                    dev.blokz.arxiver.core.network.openalex.OpenAlexClient.Companion::sidFor,
                ),
        )

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
    @Singleton
    fun bodyTextExtractor(): dev.blokz.arxiver.core.ai.BodyTextExtractor =
        dev.blokz.arxiver.core.ai.HtmlBodyTextExtractor

    @Provides
    @Singleton
    fun bodyIndexer(
        htmlStorage: dev.blokz.arxiver.core.ai.HtmlStorage,
        extractor: dev.blokz.arxiver.core.ai.BodyTextExtractor,
        ragIndexer: dev.blokz.arxiver.rag.RagIndexer,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.rag.BodyIndexer =
        dev.blokz.arxiver.rag.BodyIndexer(
            htmlStorage = htmlStorage,
            extractor = extractor,
            ragIndexer = ragIndexer,
            dispatchers = dispatchers,
            modelName = dev.blokz.arxiver.sync.EmbeddingWorker.MODEL_NAME,
        )

    /** The reader-open nudge seam (PFT.2) bound to the singleton [BodyIndexer]. */
    @Provides
    @Singleton
    fun bodyIndexTrigger(indexer: dev.blokz.arxiver.rag.BodyIndexer): dev.blokz.arxiver.rag.BodyIndexTrigger = indexer

    /** The corpus-wide "Also found in full text" leg (PFT.3), over the shared body `chunk_fts`. */
    @Provides
    @Singleton
    fun corpusBodyRetriever(
        chunkEmbeddingDao: dev.blokz.arxiver.core.database.dao.ChunkEmbeddingDao,
    ): dev.blokz.arxiver.core.search.CorpusBodyRetriever =
        dev.blokz.arxiver.core.search.CorpusBodyRetriever(
            dev.blokz.arxiver.core.search.DaoCorpusBodySource(chunkEmbeddingDao),
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
        db: dev.blokz.arxiver.core.database.ArxiverDatabase,
        chatDao: dev.blokz.arxiver.core.database.dao.ChatDao,
        ragRetriever: dev.blokz.arxiver.core.search.RagRetriever,
        providerResolver: dev.blokz.arxiver.core.ai.ProviderResolver,
        embeddingService: dev.blokz.arxiver.core.ml.EmbeddingService,
        dispatchers: DispatcherProvider,
        appScope: kotlinx.coroutines.CoroutineScope,
        toolExecutor: dev.blokz.arxiver.data.tool.ToolExecutor,
    ): dev.blokz.arxiver.data.ChatRepository =
        dev.blokz.arxiver.data.ChatRepository(
            chatDao = chatDao,
            ragRetriever = ragRetriever,
            providerResolver = providerResolver,
            assembler = dev.blokz.arxiver.chat.ChatContextAssembler(),
            previewBuilder = dev.blokz.arxiver.chat.ChatPreviewBuilder(),
            embedQuery = embeddingService::embedQuery,
            dispatchers = dispatchers,
            // The app-lifetime delete commit outlives the ChatHistoryViewModel (PC.3).
            appScope = appScope,
            // P-Tools PT.1: the loop runs the real tool registry (search_my_library) — replaces the
            // PT.0 NoToolExecutor default. Cloud providers are offered the tool; on-device isn't
            // (supportsTools=false gate), so it stays byte-identical there.
            toolLoop = dev.blokz.arxiver.data.ChatToolLoop(executor = toolExecutor),
            // P-Tools PT.0: the terminal write (assistant row + tool_invocations) runs atomically so
            // a real executor can never split the assistant COMPLETE from its tool rows.
            transaction = { block -> db.withTransaction { block() } },
        )

    /**
     * The P-Tools tool catalog (PT.1): wires `search_my_library`'s on-device search seams. The
     * semantic leg is gated behind `ModelState.Ready` so a tool call NEVER triggers a model download;
     * the registry itself holds no network dependency (zero egress).
     */
    @Provides
    @Singleton
    fun toolExecutor(
        localKeywordSearch: dev.blokz.arxiver.core.database.fts.LocalKeywordSearch,
        vectorIndex: dev.blokz.arxiver.core.search.VectorIndex,
        embeddingService: dev.blokz.arxiver.core.ml.EmbeddingService,
        modelDownloader: dev.blokz.arxiver.core.ml.ModelDownloader,
        searchDao: dev.blokz.arxiver.core.database.dao.SearchDao,
        libraryDao: dev.blokz.arxiver.core.database.dao.LibraryDao,
        paperRepository: dev.blokz.arxiver.data.PaperRepository,
        libraryRepository: dev.blokz.arxiver.data.LibraryRepository,
        semanticScholarClient: dev.blokz.arxiver.core.network.s2.SemanticScholarClient,
        openAlexClient: dev.blokz.arxiver.core.network.openalex.OpenAlexClient,
    ): dev.blokz.arxiver.data.tool.ToolExecutor =
        dev.blokz.arxiver.data.tool.ToolRegistry(
            keywordSearch = { query, includeNotes, limit ->
                localKeywordSearch.search(query, limit = limit, includeNotes = includeNotes)
            },
            semanticSearch = { query, k ->
                if (modelDownloader.state.value is dev.blokz.arxiver.core.ml.ModelState.Ready) {
                    embeddingService.embedQuery(query).getOrNull()
                        ?.let { qv -> vectorIndex.topK(qv, k).map { it.paperId to it.similarity } }
                } else {
                    null
                }
            },
            libraryPaperIds = { libraryDao.allPaperIds().toHashSet() },
            paperById = { ids -> searchDao.papersByIds(ids) },
            // EXTERNAL seams (PT.2): route through PaperRepository → ArxivApiClient → the shared arXiv
            // limiter on the @ArxivClient (AllowedHosts-gated) client. No HTTP client reaches the registry.
            searchArxiv = { filter, maxResults -> paperRepository.searchArxiv(filter, maxResults = maxResults) },
            getPaper = { id -> paperRepository.paper(dev.blokz.arxiver.core.model.ArxivRef(id)) },
            savePaper = { paperId -> libraryRepository.save(paperId) },
            isInLibrary = { paperId -> paperId in libraryDao.allPaperIds() },
            // EXTERNAL seam (PT.3): the host-gated, 1.2s-mutex-spaced S2 client. No HTTP client reaches
            // the registry — only this lambda; the structural no-okhttp-in-data/tool test stays green.
            searchSemanticScholar = { query, limit, venue, from, to ->
                semanticScholarClient.searchPapers(query, limit, venue, from, to)
            },
            // EXTERNAL seam (P-Feeds PF.1): the host-gated, 1.2s-mutex-spaced OpenAlex client. chemRxiv's own
            // API is Cloudflare-dead (search_chemrxiv now discovers via OpenAlex, filtered to the chemRxiv
            // source); the structural no-okhttp-in-data/tool test stays green — only this lambda reaches the tool.
            searchOpenAlex = { query, limit, sourceId -> openAlexClient.search(query, limit, sourceId) },
            // PS.1: persist a cached chemRxiv draft as a real `papers` row — no network (the draft carries
            // the full metadata). Origin-blind library save then rides the existing `savePaper` seam.
            importExternal = { draft -> paperRepository.saveExternalPaper(draft) },
        )

    @Provides
    @Singleton
    fun pdfDownloader(
        @ArxivClient httpClient: OkHttpClient,
        rateLimiter: ArxivRateLimiter,
        dispatchers: DispatcherProvider,
    ): dev.blokz.arxiver.core.network.pdf.PdfDownloader =
        dev.blokz.arxiver.core.network.pdf.PdfDownloader(
            httpClient,
            // R6 red line: `arxivLimiter` MUST be the injected ≥3s SINGLETON (the exact instance the Atom
            // API + HTML fetchers hold) so arXiv PDFs stay FIFO-serialized with them. The polite ~1.2s slot
            // is created here — PdfDownloader is @Singleton, so this is one app-wide polite limiter shared by
            // every non-arXiv (chemRxiv, PS.1) PDF fetch. Not a separate @Provides: a second unqualified
            // ArxivRateLimiter would be Hilt-ambiguous, and no other consumer needs the polite slot.
            dev.blokz.arxiver.core.network.pdf.PdfHostPolicy(
                arxivLimiter = rateLimiter,
                politeLimiter = ArxivRateLimiter(minSpacingMs = 1_200),
            ),
            dispatchers,
        )

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
