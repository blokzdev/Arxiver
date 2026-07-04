package dev.blokz.arxiver.core.ai

import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import dev.blokz.arxiver.core.model.ArxivId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HtmlStorageTest {
    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private lateinit var filesDir: File
    private lateinit var storage: HtmlStorage
    private val id = ArxivId("2412.19437")

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("htmlstore").toFile()
        storage = HtmlStorage(filesDir, dispatchers)
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    @Test
    fun `store then localHtml returns the body and its source`() =
        runTest {
            val stored = storage.store(id, 2, HtmlSource.AR5IV, "<p>body</p>")
            assertIs<AppResult.Success<File>>(stored)

            val cached = storage.localHtml(id, 2)
            assertNotNull(cached)
            assertEquals(HtmlSource.AR5IV, cached.source, "source survives via the .complete sentinel")
            assertTrue(cached.file.readText().contains("body"))
        }

    @Test
    fun `a missing or incomplete entry is not a cache hit`() =
        runTest {
            assertNull(storage.localHtml(id, 1), "absent → null")

            storage.store(id, 1, HtmlSource.NATIVE, "<p>x</p>")
            val complete = File(File(storage.dir(), "2412.19437v1"), ".complete")
            assertTrue(complete.delete(), "remove the sentinel to simulate a process-killed partial")
            assertNull(storage.localHtml(id, 1), "index without .complete → null")
        }

    @Test
    fun `re-storing overwrites the body atomically (PH5 two-phase text-then-images)`() =
        runTest {
            // Phase 1: placeholder body. Phase 2: the same version dir re-stored with inlined images.
            assertIs<AppResult.Success<File>>(storage.store(id, 1, HtmlSource.NATIVE, "<p>placeholders</p>"))
            assertIs<AppResult.Success<File>>(storage.store(id, 1, HtmlSource.NATIVE, "<p>data:images</p>"))

            val cached = storage.localHtml(id, 1)
            assertNotNull(cached)
            assertEquals("<p>data:images</p>", cached.file.readText(), "the second store wins (overwrite-safe)")
            assertEquals(HtmlSource.NATIVE, cached.source)
        }

    @Test
    fun `newest picks the highest version`() =
        runTest {
            storage.store(id, 1, HtmlSource.NATIVE, "v1")
            storage.store(id, 3, HtmlSource.NATIVE, "v3")
            storage.store(id, 2, HtmlSource.AR5IV, "v2")
            val newest = storage.newest(id)
            assertNotNull(newest)
            assertTrue(newest.file.readText().contains("v3"), "highest version by number")
        }

    // --- PH.6: the .position sidecar + CachedHtml.version -------------------------------------------

    @Test
    fun `position round-trips and overwrites atomically`() =
        runTest {
            storage.storePosition(id, 1, ReaderPosition("S2.SS1", 340, 0.41f))
            assertEquals(ReaderPosition("S2.SS1", 340, 0.41f), storage.readPosition(id, 1))

            storage.storePosition(id, 1, ReaderPosition(null, 0, 0.9f))
            assertEquals(ReaderPosition(null, 0, 0.9f), storage.readPosition(id, 1))
            val vdir = File(filesDir, "html/2412.19437v1")
            assertTrue(vdir.listFiles()!!.none { it.name.endsWith(".part") }, "no tmp residue")
        }

    @Test
    fun `absent or corrupt position reads null — open at top`() =
        runTest {
            assertNull(storage.readPosition(id, 1))
            val vdir = File(filesDir, "html/2412.19437v1").apply { mkdirs() }
            File(vdir, ".position").writeText("not|a|valid")
            assertNull(storage.readPosition(id, 1))
            File(vdir, ".position").writeText("2|S1|10|0.5") // unknown format version
            assertNull(storage.readPosition(id, 1))
        }

    @Test
    fun `the sidecar survives a phase-2 re-store and is invisible to the cache gates`() =
        runTest {
            storage.store(id, 1, HtmlSource.NATIVE, "<p>phase 1</p>")
            storage.storePosition(id, 1, ReaderPosition("S1", 12, 0.1f))
            storage.store(id, 1, HtmlSource.NATIVE, "<p>phase 2 with figures</p>")

            assertEquals(ReaderPosition("S1", 12, 0.1f), storage.readPosition(id, 1))
            assertEquals("<p>phase 2 with figures</p>", storage.localHtml(id, 1)!!.file.readText())
        }

    @Test
    fun `positions are isolated per version`() =
        runTest {
            storage.storePosition(id, 1, ReaderPosition("S1", 1, 0.1f))
            storage.storePosition(id, 2, ReaderPosition("S9", 9, 0.9f))
            assertEquals("S1", storage.readPosition(id, 1)!!.anchorId)
            assertEquals("S9", storage.readPosition(id, 2)!!.anchorId)
        }

    @Test
    fun `CachedHtml carries the served version from localHtml and newest — legacy slash-v ids included`() =
        runTest {
            storage.store(id, 3, HtmlSource.NATIVE, "<p>v3</p>")
            assertEquals(3, storage.localHtml(id, 3)!!.version)

            val legacy = ArxivId("solv-int/9701001")
            storage.store(legacy, 2, HtmlSource.AR5IV, "<p>legacy v2</p>")
            storage.store(legacy, 1, HtmlSource.AR5IV, "<p>legacy v1</p>")
            val newest = storage.newest(legacy)!!
            assertEquals(2, newest.version)
            assertEquals("<p>legacy v2</p>", newest.file.readText())
        }
}
