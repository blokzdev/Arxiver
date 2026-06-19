package dev.blokz.arxiver.core.ml

import dev.blokz.arxiver.core.common.AppResult
import dev.blokz.arxiver.core.common.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.MessageDigest
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ModelDownloaderTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer

    private val dispatchers =
        object : DispatcherProvider {
            override val io: CoroutineDispatcher = Dispatchers.IO
            override val default: CoroutineDispatcher = Dispatchers.Default
            override val main: CoroutineDispatcher = Dispatchers.Default
        }

    private val payload = "fake-onnx-model-bytes".toByteArray()
    private val payloadSha =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { "%02x".format(it) }

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun downloader(sha: String) =
        ModelDownloader(
            httpClient = OkHttpClient(),
            dispatchers = dispatchers,
            modelDir = tmp.root,
            spec =
                ModelDownloader.ModelSpec(
                    fileName = "model.onnx",
                    url = server.url("/model.onnx").toString(),
                    sha256 = sha,
                    dimensions = 384,
                    displayName = "test model",
                ),
        )

    @Test
    fun `downloads, verifies and persists`() =
        runTest {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            val downloader = downloader(payloadSha)

            val result = downloader.ensureDownloaded()

            val file = assertIs<AppResult.Success<java.io.File>>(result).value
            assertTrue(file.exists())
            assertEquals(payload.size.toLong(), file.length())
            assertIs<ModelState.Ready>(downloader.state.value)

            // Second call is a no-op (no second request).
            downloader.ensureDownloaded()
            assertEquals(1, server.requestCount)
        }

    @Test
    fun `checksum mismatch rejects file`() =
        runTest {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            val downloader = downloader(sha = "deadbeef".repeat(8))

            val result = downloader.ensureDownloaded()

            assertIs<AppResult.Failure>(result)
            assertFalse(downloader.modelFile.exists())
            assertIs<ModelState.Failed>(downloader.state.value)
        }

    @Test
    fun `http error surfaces upstream failure`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            val result = downloader(payloadSha).ensureDownloaded()
            assertIs<AppResult.Failure>(result)
        }

    @Test
    fun `download removes stale same-kind siblings but keeps other model kinds`() =
        runTest {
            // A superseded variant of the same kind, plus an unrelated embedding model, already on disk.
            java.io.File(tmp.root, "gemma-old-web.litertlm").writeText("old variant")
            java.io.File(tmp.root, "bge-small.onnx").writeText("keep me")
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            val downloader =
                ModelDownloader(
                    httpClient = OkHttpClient(),
                    dispatchers = dispatchers,
                    modelDir = tmp.root,
                    spec =
                        ModelDownloader.ModelSpec(
                            fileName = "gemma-new.litertlm",
                            url = server.url("/gemma-new.litertlm").toString(),
                            sha256 = payloadSha,
                            dimensions = 0,
                            displayName = "gemma",
                        ),
                )

            downloader.ensureDownloaded()

            assertFalse(java.io.File(tmp.root, "gemma-old-web.litertlm").exists()) // stale .litertlm purged
            assertTrue(java.io.File(tmp.root, "bge-small.onnx").exists()) // other extension untouched
            assertTrue(java.io.File(tmp.root, "gemma-new.litertlm").exists()) // new model present
        }

    @Test
    fun `delete resets state`() =
        runTest {
            server.enqueue(MockResponse().setBody(okio.Buffer().write(payload)))
            val downloader = downloader(payloadSha)
            downloader.ensureDownloaded()
            downloader.delete()
            assertFalse(downloader.modelFile.exists())
            assertIs<ModelState.NotDownloaded>(downloader.state.value)
        }
}
