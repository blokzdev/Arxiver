package dev.blokz.arxiver.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.database.ArxiverDatabase
import dev.blokz.arxiver.core.database.toEntity
import dev.blokz.arxiver.core.model.ArxivId
import dev.blokz.arxiver.core.model.ArxivRef
import dev.blokz.arxiver.core.model.ExternalRef
import dev.blokz.arxiver.core.model.Paper
import dev.blokz.arxiver.core.model.PaperSource
import dev.blokz.arxiver.core.model.Source
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.elementDescriptors
import kotlinx.serialization.descriptors.elementNames
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {
    private lateinit var db: ArxiverDatabase
    private lateinit var backupManager: BackupManager
    private var restoredRoutines = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, ArxiverDatabase::class.java).build()
        backupManager =
            BackupManager(
                libraryExporter = LibraryExporter(db.libraryDao(), db.paperDao()),
                paperDao = db.paperDao(),
                libraryDao = db.libraryDao(),
                followDao = db.followDao(),
                routineDao = db.routineDao(),
                routineRestorer = { name, url ->
                    restoredRoutines += name to url
                    db.routineDao().insertConfig(
                        dev.blokz.arxiver.core.database.entity.RoutineConfigEntity(
                            name = name,
                            triggerUrl = url,
                            tokenAlias = "restored",
                            createdAt = 0,
                        ),
                    )
                },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun paper(
        id: String,
        title: String,
    ) = Paper(
        ref = ArxivRef(ArxivId(id)),
        latestVersion = 1,
        title = title,
        abstract = "An abstract about $title.",
        publishedAt = Instant.parse("2024-03-02T10:00:00Z"),
        updatedAt = Instant.parse("2024-03-02T10:00:00Z"),
        primaryCategory = "cs.LG",
        categories = listOf("cs.LG"),
        authors = listOf("Ada Researcher"),
        comment = "10 pages",
    )

    private suspend fun seedSourceData() {
        val p = paper("2401.00001", "Backup Subject")
        db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)
        db.libraryDao().upsertEntry(
            dev.blokz.arxiver.core.database.entity.LibraryEntryEntity(
                paperId = "2401.00001",
                addedAt = 100,
                status = "read",
                rating = 5,
            ),
        )
        db.libraryDao().insertNote(
            dev.blokz.arxiver.core.database.entity.NoteEntity(
                paperId = "2401.00001",
                content = "important insight",
                createdAt = 1,
                updatedAt = 1,
            ),
        )
        db.libraryDao().insertTag(dev.blokz.arxiver.core.database.entity.TagEntity(name = "ssm"))
        db.libraryDao().tagIdByName("ssm")?.let {
            db.libraryDao().addPaperTag(
                dev.blokz.arxiver.core.database.entity.PaperTagCrossRef("2401.00001", it),
            )
        }
        val collectionId =
            db.libraryDao().createCollection(
                dev.blokz.arxiver.core.database.entity.CollectionEntity(name = "Favorites", createdAt = 1),
            )
        db.libraryDao().addToCollection(
            dev.blokz.arxiver.core.database.entity.CollectionPaperCrossRef(collectionId, "2401.00001", 1),
        )
        db.followDao().insert(
            dev.blokz.arxiver.core.database.entity.FollowEntity(
                type = "category",
                value = "cs.LG",
                label = "Machine Learning",
                createdAt = 1,
            ),
        )
        db.routineDao().insertConfig(
            dev.blokz.arxiver.core.database.entity.RoutineConfigEntity(
                name = "Digest",
                triggerUrl = "https://claude.ai/api/routines/abc/trigger",
                tokenAlias = "alias-1",
                createdAt = 1,
            ),
        )
    }

    @Test
    fun `backup never contains tokens or aliases`() =
        runTest {
            seedSourceData()
            val json = backupManager.export()
            assertTrue("https://claude.ai/api/routines/abc/trigger" in json)
            assertFalse("alias-1" in json)
            assertFalse("tokenAlias" in json)
            assertFalse("token_alias" in json)
        }

    @Test
    fun `export then import into a fresh database restores everything`() =
        runTest {
            seedSourceData()
            val json = backupManager.export()

            // Fresh database = new device.
            db.close()
            setUp()

            val summary = backupManager.import(json)

            assertEquals(1, summary.papers)
            assertEquals(1, summary.follows)
            assertEquals(1, summary.collections)
            assertEquals(1, summary.routinesNeedingTokens)
            assertEquals(listOf("Digest" to "https://claude.ai/api/routines/abc/trigger"), restoredRoutines)

            val entry = db.libraryDao().observeEntry("2401.00001").first()
            assertEquals("read", entry?.status)
            assertEquals(5, entry?.rating)
            assertEquals(
                listOf("important insight"),
                db.libraryDao().notesFor("2401.00001").map { it.content },
            )
            assertEquals(
                listOf("ssm"),
                db.libraryDao().observeTagsFor("2401.00001").first().map { it.name },
            )
            assertEquals(
                listOf("Ada Researcher"),
                db.paperDao().paperWithRelations("2401.00001")?.authors,
            )
            // Restored routine is flagged for re-authentication.
            assertTrue(db.routineDao().observeConfigs().first().single().authInvalid)
        }

    @Test
    fun `import is idempotent and does not duplicate`() =
        runTest {
            seedSourceData()
            val json = backupManager.export()
            backupManager.import(json)
            backupManager.import(json)

            assertEquals(1, db.libraryDao().count())
            assertEquals(1, db.libraryDao().notesFor("2401.00001").size)
            assertEquals(1, db.libraryDao().observeTagsFor("2401.00001").first().size)
            assertEquals(1, db.libraryDao().observeCollections().first().size)
            // Routine already present (same URL) — not restored twice.
            assertEquals(1, db.routineDao().observeConfigs().first().size)
        }

    @Test
    fun `import rejects foreign schema`() =
        runTest {
            val result = runCatching { backupManager.import("""{"schema":"other/v9","exportedAt":"x","papers":[]}""") }
            assertTrue(result.isFailure)
        }

    // --- P-Sources PS.1: the backup URL-mangle fix + v1 back-compat ---

    private fun chemPaper() =
        Paper(
            ref = ExternalRef(Source.CHEMRXIV, "10.26434/chemrxiv-2024-xyz"),
            latestVersion = 1,
            title = "A Chemistry Preprint",
            abstract = "An abstract about chemistry.",
            publishedAt = Instant.parse("2024-05-01T00:00:00Z"),
            updatedAt = Instant.parse("2024-05-01T00:00:00Z"),
            primaryCategory = "",
            categories = emptyList(),
            authors = listOf("Marie Curie"),
            doi = "10.26434/chemrxiv-2024-xyz",
            pdfUrl = "https://chemrxiv.org/engage/chemrxiv/assets/xyz.pdf",
            source = PaperSource.MANUAL,
        )

    @Test
    fun `a chemrxiv paper round-trips with its real url and origin, never mangled to arxiv`() =
        runTest {
            val p = chemPaper()
            db.paperDao().upsertPaperWithRelations(p.toEntity(), p.authors, p.categories)
            db.libraryDao().upsertEntry(
                dev.blokz.arxiver.core.database.entity.LibraryEntryEntity(
                    paperId = p.ref.storageId,
                    addedAt = 1,
                    status = "to_read",
                    rating = null,
                ),
            )

            val json = backupManager.export()
            // The real chemRxiv URL is carried verbatim; NO synthesized arxiv.org URL for a non-arXiv paper.
            assertTrue("https://chemrxiv.org/engage/chemrxiv/assets/xyz.pdf" in json)
            assertFalse("arxiv.org/pdf/chemrxiv" in json, "the pre-P-Sources URL-mangle bug must not recur")
            assertFalse("arxiv.org/abs/chemrxiv" in json)
            // No raw PDF bytes / HTML ever enter the backup (red line).
            assertFalse("%PDF" in json)

            db.close()
            setUp()
            backupManager.import(json)

            val restored = db.paperDao().paperWithRelations("chemrxiv:10.26434/chemrxiv-2024-xyz")
            assertEquals("chemrxiv", restored?.paper?.origin)
            assertEquals("https://chemrxiv.org/engage/chemrxiv/assets/xyz.pdf", restored?.paper?.pdfUrl)
            assertEquals("A Chemistry Preprint", restored?.paper?.title)
            assertEquals("to_read", db.libraryDao().observeEntry("chemrxiv:10.26434/chemrxiv-2024-xyz").first()?.status)
        }

    @Test
    fun `a legacy v1 backup (arxivId, absUrl, no origin) still imports and re-synthesizes the arxiv pdf`() =
        runTest {
            // A hand-written pre-P-Sources v1 file: paper key is "arxivId", carries "absUrl", omits
            // origin/pdfUrl. @JsonNames maps arxivId->paperId, ignoreUnknownKeys drops absUrl, origin
            // defaults to arxiv, and toEntity re-synthesizes the arXiv PDF url exactly as v1 stored it.
            val v1 =
                """
                {
                  "schema": "arxiver-backup/v1",
                  "exportedAt": "2024-01-01T00:00:00Z",
                  "papers": [
                    {
                      "arxivId": "2401.00001", "version": 2, "title": "Legacy Paper", "abstract": "old",
                      "authors": ["Ada Researcher"], "primaryCategory": "cs.LG", "categories": ["cs.LG"],
                      "published": "2024-03-02T10:00:00Z", "updated": "2024-03-02T10:00:00Z", "doi": null,
                      "absUrl": "https://arxiv.org/abs/2401.00001", "status": "read", "rating": 4,
                      "addedAt": "2024-03-02T10:00:00Z", "tags": [], "notes": []
                    }
                  ]
                }
                """.trimIndent()
            backupManager.import(v1)
            val restored = db.paperDao().paperWithRelations("2401.00001")
            assertEquals("arxiv", restored?.paper?.origin)
            assertEquals("https://arxiv.org/pdf/2401.00001v2", restored?.paper?.pdfUrl)
            assertEquals("Legacy Paper", restored?.paper?.title)
            assertEquals("read", db.libraryDao().observeEntry("2401.00001").first()?.status)
        }

    // --- P-Chat PC.0: the chat-never-in-backup red line, converted from code-absence to a test ---

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `the importable backup schema carries exactly the six known surfaces - chat can never leak in`() {
        // Descriptor-based on purpose: a raw-JSON substring match would false-positive on any
        // paper whose title mentions "chat". If a future field is ever added deliberately, this
        // test forces the decision to be explicit — chat/session/message fields stay forbidden.
        val elements = ArxiverBackup.serializer().descriptor.elementNames.toSet()
        assertEquals(
            setOf("schema", "exportedAt", "papers", "follows", "collections", "routines"),
            elements,
        )
    }

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @Test
    fun `no element at any depth of the export schemas is chat-shaped`() {
        // The top-level pin above can't see NESTED DTOs — a future `chats` field on
        // ExportedPaper or BackupCollection would serialize into the importable backup
        // with it still green. Walk the whole descriptor tree of BOTH export schemas.
        fun collect(
            d: SerialDescriptor,
            names: MutableSet<String>,
            seen: MutableSet<String>,
        ) {
            if (!seen.add(d.serialName)) return
            names.addAll(d.elementNames)
            d.elementDescriptors.forEach { collect(it, names, seen) }
        }

        // P-Tools PT.0: tool-activity surfaces write model-derived query strings (PII). Extend the
        // wall to catch a mis-added tool/invocation/query/activity field at any nesting depth BEFORE
        // the tool_invocations table lands. Verified no existing element name collides with these.
        val forbidden = listOf("chat", "session", "message", "conversation", "tool", "invocation", "query", "activity")
        for (root in listOf(ArxiverBackup.serializer(), LibraryExport.serializer())) {
            val names = mutableSetOf<String>()
            collect(root.descriptor, names, mutableSetOf())
            val leaks = names.filter { n -> forbidden.any { n.lowercase().contains(it) } }
            assertTrue(
                leaks.isEmpty(),
                "chat-shaped element(s) inside ${root.descriptor.serialName}: $leaks — " +
                    "chat content must never enter an importable/exportable schema",
            )
        }
    }
}
