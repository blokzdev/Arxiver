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
}
