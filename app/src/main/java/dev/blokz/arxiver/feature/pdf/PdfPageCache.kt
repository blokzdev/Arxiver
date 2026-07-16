package dev.blokz.arxiver.feature.pdf

import android.graphics.Bitmap
import kotlinx.coroutines.CompletableDeferred

/**
 * A single-owner, **byte-bounded** LRU cache of rendered PDF page bitmaps (reader hardening, 2026-07-16 —
 * follow-up to the pinch-blank fix in [PdfViewerScreen]).
 *
 * The pinch-blank fix deleted an eager per-item `Bitmap.recycle()` (it raced the zoom graphicsLayer's retained
 * draw), leaving page-bitmap lifetime to GC. This cache restores a *safe* memory bound AND adds scroll-back reuse,
 * designed to avoid every trap that approach can hit:
 *  - **Bounded by TOTAL bytes** (`Bitmap.getByteCount`), not a fixed page count — co-designed with the per-bitmap
 *    [pdfTargetWidth] heap ceiling so a wide foldable (big bitmaps) can't be bounded by a naive count. (Android's
 *    canonical `LruCache` sizing convention; ~1/6 of the heap by default.)
 *  - A bitmap is recycled **only** when an OFF-WINDOW page is evicted — never a [pin]ned (currently-composed) one —
 *    so a recycled bitmap can never reach a live `Image` (the exact "Canvas: trying to use a recycled bitmap"
 *    class). A freshly-rendered page is the most-recently-used entry, so [trim] (coldest-first) can't evict it out
 *    from under the caller even before its pin commits.
 *  - **Single-flight per index**: a fast scroll can render the same page from two coroutines; only one renders, the
 *    other awaits it (no duplicate render, no leaked loser).
 *  - A revisited page is a cache **hit** — no re-render (the reuse win over the GC-only approach).
 *
 * Thread-safety: all map + accounting state is guarded by [lock] (a fast, non-suspending monitor, so the Compose
 * effect can [pin]/[unpin] on the main thread cheaply); the actual page render runs OUTSIDE the lock (PdfRenderer
 * already serialises via its own mutex), coordinated by one [CompletableDeferred] per in-flight index.
 */
internal class PdfPageCache(
    private val maxBytes: Long = Runtime.getRuntime().maxMemory() / 6,
) {
    private val lock = Any()

    // accessOrder = true → the iterator yields least-recently-used first, so trim() evicts the coldest page.
    private val cache = LinkedHashMap<Int, Bitmap>(16, 0.75f, true)
    private val pinned = HashSet<Int>()
    private val inFlight = HashMap<Int, CompletableDeferred<Bitmap?>>()
    private var bytes = 0L

    /** Mark [index] as on-screen (composed) — it is never evicted/recycled until [unpin]ned. Idempotent. */
    fun pin(index: Int) {
        synchronized(lock) { pinned.add(index) }
    }

    /** Mark [index] off-screen (evictable) and trim now that the on-screen window has shrunk. */
    fun unpin(index: Int) {
        synchronized(lock) {
            pinned.remove(index)
            trim()
        }
    }

    /**
     * The cached bitmap for [index], else [render] it once (single-flight) and cache it. Never returns a recycled
     * bitmap. A hit promotes the entry to most-recently-used.
     */
    suspend fun getOrRender(
        index: Int,
        render: suspend () -> Bitmap?,
    ): Bitmap? {
        while (true) {
            var await: CompletableDeferred<Bitmap?>? = null
            var mine: CompletableDeferred<Bitmap?>? = null
            synchronized(lock) {
                val cached = cache[index]
                if (cached != null && !cached.isRecycled) return cached // hit (accessOrder promoted it)
                val flight = inFlight[index]
                if (flight != null) {
                    await = flight
                } else {
                    mine = CompletableDeferred<Bitmap?>().also { inFlight[index] = it }
                }
            }
            val pending = await
            if (pending != null) {
                val awaited = pending.await()
                if (awaited != null && !awaited.isRecycled) return awaited
                // The render we awaited failed (or its bitmap was evicted before our read) → loop and re-resolve.
            } else {
                val deferred = mine!!
                val rendered = runCatching { render() }.getOrNull()
                synchronized(lock) {
                    inFlight.remove(index)
                    if (rendered != null && !rendered.isRecycled) {
                        cache[index] = rendered // most-recently-used (tail) → protected from this trim()
                        bytes += rendered.byteCount
                        trim()
                    }
                }
                deferred.complete(rendered)
                return rendered
            }
        }
    }

    /** Recycle every cached bitmap — reader close, or a width change that re-rasterises the whole document. */
    fun evictAll() {
        synchronized(lock) {
            cache.values.forEach { if (!it.isRecycled) it.recycle() }
            cache.clear()
            pinned.clear()
            inFlight.clear()
            bytes = 0L
        }
    }

    /** Evict coldest-first until under [maxBytes], skipping pinned (on-screen) pages. Caller holds [lock]. */
    private fun trim() {
        if (bytes <= maxBytes) return
        val iterator = cache.entries.iterator()
        while (bytes > maxBytes && iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in pinned) continue
            val bitmap = entry.value
            iterator.remove()
            bytes -= bitmap.byteCount
            if (!bitmap.isRecycled) bitmap.recycle()
        }
    }

    // --- test seams (internal) ---
    internal fun sizeBytesForTest(): Long = synchronized(lock) { bytes }

    internal fun cachedIndicesForTest(): List<Int> = synchronized(lock) { cache.keys.toList() }
}
