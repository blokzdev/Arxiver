package dev.blokz.arxiver.core.pdf

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PFT.5.2 — the extractor's CI coverage. The pure `collapseWhitespace` shape (matches the HTML extractor) +
 * an end-to-end extract over a committed, valid, text-extractable PDF fixture (proves `PDFBoxResourceLoader.
 * init` + `PDDocument.load(File)` + `PDFTextStripper` wire up) + the never-throws contract on junk. Real
 * two-column / ligature reading-order quality is device-verified (VERIFICATION §P-Reader2), not asserted here.
 */
@RunWith(RobolectricTestRunner::class)
class PdfBodyTextExtractorTest {
    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.Unconfined
            override val default: CoroutineDispatcher = Dispatchers.Unconfined
            override val main: CoroutineDispatcher = Dispatchers.Unconfined
        }

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `collapseWhitespace normalizes every run and trims`() {
        assertEquals("a b c", PdfBodyTextExtractor.collapseWhitespace("  a\n\n b\t\tc  "))
        assertEquals("one two", PdfBodyTextExtractor.collapseWhitespace("one\r\n two"))
        assertEquals("", PdfBodyTextExtractor.collapseWhitespace("   \n\t "))
        assertEquals("x", PdfBodyTextExtractor.collapseWhitespace("x"))
    }

    @Test
    fun `extracts whitespace-collapsed body text from a real PDF`() =
        runTest {
            val pdf =
                File.createTempFile("sample", ".pdf").apply {
                    writeBytes(java.util.Base64.getDecoder().decode(SAMPLE_PDF_B64))
                    deleteOnExit()
                }

            val text = PdfBodyTextExtractor(context, dispatchers).extract(pdf)

            assertTrue("quokka reading world" in text, "expected the extracted body, was: '$text'")
            assertTrue("\n" !in text && "  " !in text, "output must be whitespace-collapsed: '$text'")
        }

    @Test
    fun `a malformed or non-PDF file yields empty string, never throws`() =
        runTest {
            val junk =
                File.createTempFile("junk", ".pdf").apply {
                    writeText("this is definitely not a PDF document")
                    deleteOnExit()
                }
            assertEquals("", PdfBodyTextExtractor(context, dispatchers).extract(junk))
        }

    private companion object {
        // A minimal, valid, single-page PDF (Helvetica / WinAnsiEncoding) whose body reads
        // "Hello quokka reading world PFT5 universal fulltext" — embedded inline (not a test resource) so the
        // fixture survives Robolectric's sandbox classloader, which doesn't resolve java_res resources.
        const val SAMPLE_PDF_B64 =
            "JVBERi0xLjQKMSAwIG9iago8PCAvVHlwZSAvQ2F0YWxvZyAvUGFnZXMgMiAwIFIgPj4KZW5kb2Jq" +
                "CjIgMCBvYmoKPDwgL1R5cGUgL1BhZ2VzIC9LaWRzIFszIDAgUl0gL0NvdW50IDEgPj4KZW5kb2Jq" +
                "CjMgMCBvYmoKPDwgL1R5cGUgL1BhZ2UgL1BhcmVudCAyIDAgUiAvTWVkaWFCb3ggWzAgMCA2MTIg" +
                "NzkyXSAvQ29udGVudHMgNCAwIFIgL1Jlc291cmNlcyA8PCAvRm9udCA8PCAvRjEgNSAwIFIgPj4g" +
                "Pj4gPj4KZW5kb2JqCjQgMCBvYmoKPDwgL0xlbmd0aCA4MSA+PgpzdHJlYW0KQlQgL0YxIDE4IFRm" +
                "IDcyIDcwMCBUZCAoSGVsbG8gcXVva2thIHJlYWRpbmcgd29ybGQgUEZUNSB1bml2ZXJzYWwgZnVs" +
                "bHRleHQpIFRqIEVUCmVuZHN0cmVhbQplbmRvYmoKNSAwIG9iago8PCAvVHlwZSAvRm9udCAvU3Vi" +
                "dHlwZSAvVHlwZTEgL0Jhc2VGb250IC9IZWx2ZXRpY2EgL0VuY29kaW5nIC9XaW5BbnNpRW5jb2Rp" +
                "bmcgPj4KZW5kb2JqCnhyZWYKMCA2CjAwMDAwMDAwMDAgNjU1MzUgZiAKMDAwMDAwMDAwOSAwMDAw" +
                "MCBuIAowMDAwMDAwMDU4IDAwMDAwIG4gCjAwMDAwMDAxMTUgMDAwMDAgbiAKMDAwMDAwMDI0MSAw" +
                "MDAwMCBuIAowMDAwMDAwMzcyIDAwMDAwIG4gCnRyYWlsZXIKPDwgL1NpemUgNiAvUm9vdCAxIDAg" +
                "UiA+PgpzdGFydHhyZWYKNDY5CiUlRU9G"
    }
}
