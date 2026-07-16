package dev.blokz.arxiver.feature.pdf

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The byte-bounded LRU page-bitmap cache: reuse, byte-budget eviction, pin-safety, single-flight. */
@RunWith(RobolectricTestRunner::class)
class PdfPageCacheTest {
    // ARGB_8888 100×100 = 40,000 B/page — a fixed, known byteCount so the budget math is exact.
    private val pageBytes = 100L * 100L * 4L

    private fun page(): Bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)

    @Test
    fun `a hit returns the same instance and renders once`() =
        runBlocking {
            val cache = PdfPageCache(maxBytes = 10 * pageBytes)
            val renders = AtomicInteger()
            val render: suspend () -> Bitmap? = {
                renders.incrementAndGet()
                page()
            }

            val a = cache.getOrRender(0, render)
            val b = cache.getOrRender(0, render)

            assertSame(a, b, "second call is a cache hit")
            assertEquals(1, renders.get(), "a hit does not re-render")
        }

    @Test
    fun `evicts the coldest unpinned page when over the byte budget`() =
        runBlocking {
            val cache = PdfPageCache(maxBytes = 2 * pageBytes) // holds 2 pages
            val b0 = cache.getOrRender(0) { page() }
            cache.getOrRender(1) { page() }
            cache.getOrRender(2) { page() } // 3rd pushes over budget → evict the coldest (index 0)

            assertEquals(listOf(1, 2), cache.cachedIndicesForTest())
            assertTrue(b0!!.isRecycled, "the evicted off-window bitmap is recycled")
            assertTrue(cache.sizeBytesForTest() <= 2 * pageBytes)
        }

    @Test
    fun `a pinned page is never evicted or recycled even over budget`() =
        runBlocking {
            val cache = PdfPageCache(maxBytes = 2 * pageBytes)
            cache.pin(0)
            val b0 = cache.getOrRender(0) { page() }
            cache.getOrRender(1) { page() }
            cache.getOrRender(2) { page() }
            cache.getOrRender(3) { page() }

            assertTrue(0 in cache.cachedIndicesForTest(), "the pinned page stays cached")
            assertFalse(b0!!.isRecycled, "a pinned (composed) page's bitmap is never recycled")
        }

    @Test
    fun `a freshly rendered page is not evicted by its own trim (MRU-protected)`() =
        runBlocking {
            // Budget holds only 1 page; render two distinct UNPINNED pages. The 2nd render's trim must evict the
            // cold 1st, never the just-rendered 2nd (which is most-recently-used) — the pin-commit-window guard.
            val cache = PdfPageCache(maxBytes = 1 * pageBytes)
            cache.getOrRender(0) { page() }
            val b1 = cache.getOrRender(1) { page() }

            assertEquals(listOf(1), cache.cachedIndicesForTest())
            assertFalse(b1!!.isRecycled, "the just-rendered page survives its own trim")
        }

    @Test
    fun `unpin makes a page evictable and trims it under pressure`() =
        runBlocking {
            val cache = PdfPageCache(maxBytes = 2 * pageBytes)
            cache.pin(0)
            cache.getOrRender(0) { page() }
            cache.getOrRender(1) { page() }
            cache.getOrRender(2) { page() } // 0 pinned → 1 evicted; cache {0,2}

            assertEquals(listOf(0, 2), cache.cachedIndicesForTest().sorted())

            cache.unpin(0) // 0 now evictable
            cache.getOrRender(3) { page() } // over budget → 0 (coldest, now unpinned) evicted

            assertFalse(0 in cache.cachedIndicesForTest(), "an unpinned cold page is evicted under pressure")
        }

    @Test
    fun `evictAll recycles every cached bitmap`() =
        runBlocking {
            val cache = PdfPageCache(maxBytes = 10 * pageBytes)
            val b0 = cache.getOrRender(0) { page() }
            val b1 = cache.getOrRender(1) { page() }

            cache.evictAll()

            assertTrue(b0!!.isRecycled && b1!!.isRecycled, "evictAll recycles all cached bitmaps")
            assertTrue(cache.cachedIndicesForTest().isEmpty())
            assertEquals(0L, cache.sizeBytesForTest())
        }

    @Test
    fun `single-flight - two concurrent renders of one index render once and share the bitmap`() =
        runBlocking(Dispatchers.Default) {
            val cache = PdfPageCache(maxBytes = 10 * pageBytes)
            val renders = AtomicInteger()
            val gate = CompletableDeferred<Unit>()
            val render: suspend () -> Bitmap? = {
                renders.incrementAndGet()
                gate.await() // hold the render open so the second caller overlaps it
                page()
            }

            val a = async { cache.getOrRender(0, render) }
            delay(50) // 'a' has entered render (count=1) and is awaiting the gate
            val b = async { cache.getOrRender(0, render) } // finds the in-flight render → awaits it
            delay(50)
            gate.complete(Unit)
            val ra = a.await()
            val rb = b.await()

            assertEquals(1, renders.get(), "the page is rendered exactly once")
            assertSame(ra, rb, "both callers get the same bitmap")
        }
}
