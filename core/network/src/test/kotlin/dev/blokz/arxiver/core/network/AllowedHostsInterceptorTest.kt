package dev.blokz.arxiver.core.network

import okhttp3.Call
import okhttp3.Connection
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AllowedHostsInterceptorTest {
    private val interceptor = AllowedHostsInterceptor()

    /** Minimal [Interceptor.Chain] recording whether the request was allowed through. */
    private class FakeChain(private val request: Request) : Interceptor.Chain {
        var proceeded = false
            private set

        override fun request(): Request = request

        override fun proceed(request: Request): Response {
            proceeded = true
            return Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_2)
                .code(200)
                .message("OK")
                .body("ok".toResponseBody(null))
                .build()
        }

        override fun connection(): Connection? = null

        override fun call(): Call = throw UnsupportedOperationException()

        override fun connectTimeoutMillis(): Int = 0

        override fun withConnectTimeout(
            timeout: Int,
            unit: TimeUnit,
        ): Interceptor.Chain = this

        override fun readTimeoutMillis(): Int = 0

        override fun withReadTimeout(
            timeout: Int,
            unit: TimeUnit,
        ): Interceptor.Chain = this

        override fun writeTimeoutMillis(): Int = 0

        override fun withWriteTimeout(
            timeout: Int,
            unit: TimeUnit,
        ): Interceptor.Chain = this
    }

    private fun req(url: String) = Request.Builder().url(url).build()

    @Test
    fun `an allowed https host proceeds`() {
        val chain = FakeChain(req("https://arxiv.org/html/2412.19437v1"))
        val response = interceptor.intercept(chain)
        assertTrue(chain.proceeded)
        assertTrue(response.isSuccessful)
    }

    @Test
    fun `a non-https request is rejected before proceeding`() {
        val chain = FakeChain(req("http://arxiv.org/x"))
        assertFailsWith<IOException> { interceptor.intercept(chain) }
        assertFalse(chain.proceeded, "must not proceed an http request")
    }

    @Test
    fun `a disallowed host is rejected before proceeding`() {
        val chain = FakeChain(req("https://evil.example/x"))
        assertFailsWith<IOException> { interceptor.intercept(chain) }
        assertFalse(chain.proceeded, "must not proceed a disallowed host")
    }

    @Test
    fun `chemrxiv proceeds but an off-host asset CDN hop is blocked (PT4 redirect red-line)`() {
        // Hop 1 — the allowlisted chemRxiv API host proceeds.
        val apiHop = FakeChain(req("https://chemrxiv.org/engage/chemrxiv/public-api/v1/items?term=x"))
        interceptor.intercept(apiHop)
        assertTrue(apiHop.proceeded)
        // A redirect hop to an off-host asset CDN sub-domain is a NEW disallowed host. As a NETWORK
        // interceptor this re-fires per hop and throws before the socket — FakeChain models one hop
        // faithfully (the live end-to-end 302 rides VERIFICATION §Q-PT: MockWebServer's localhost origin
        // is itself not allowlisted, so hop-1-through-the-real-allowlist can't be exercised in a unit test).
        val assetHop = FakeChain(req("https://assets.chemrxiv.org/orp/resource/item/x/original/paper.pdf"))
        assertFailsWith<IOException> { interceptor.intercept(assetHop) }
        assertFalse(assetHop.proceeded, "an off-host asset origin must be blocked per hop")
    }

    @Test
    fun `as an application interceptor a disallowed host opens no socket`() {
        // The app-interceptor slot runs before ConnectInterceptor, so this throws with NO real network
        // (we never reach evil.example). Proves the pre-connection gate end-to-end.
        val client = OkHttpClient.Builder().addInterceptor(AllowedHostsInterceptor()).build()
        assertFailsWith<IOException> {
            client.newCall(req("https://evil.example/x")).execute()
        }
    }

    @Test
    fun `the gate is present on a gated client and absent on a bare client (AI-key uncoupling)`() {
        val gated =
            OkHttpClient.Builder()
                .addInterceptor(AllowedHostsInterceptor())
                .addNetworkInterceptor(AllowedHostsInterceptor())
                .build()
        assertTrue(gated.interceptors.any { it is AllowedHostsInterceptor })
        assertTrue(gated.networkInterceptors.any { it is AllowedHostsInterceptor })

        val bare = OkHttpClient()
        assertFalse(bare.interceptors.any { it is AllowedHostsInterceptor }, "bare client must carry no host gate")
        assertFalse(bare.networkInterceptors.any { it is AllowedHostsInterceptor })
    }
}
