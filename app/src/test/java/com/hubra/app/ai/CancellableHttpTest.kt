package com.hubra.app.ai

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CancellableHttpTest {
    @Test fun cancellationDuringBodyReadCancelsTheCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("data: first\n\ndata: second\n\n").throttleBody(1, 1, TimeUnit.SECONDS))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            val reading = CountDownLatch(1)
            val job = launch(Dispatchers.IO) {
                CancellableHttp.read(call) { response -> reading.countDown(); response.body!!.string() }
            }
            assertTrue(withContext(Dispatchers.IO) { reading.await(5, TimeUnit.SECONDS) })
            withTimeout(2000) { job.cancelAndJoin() }
            assertTrue(call.isCanceled())
        }
    }
    @Test fun successfulResponseReturnsParsedBody() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("done"))
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            assertEquals("done", CancellableHttp.read(call) { it.body!!.string() })
        }
    }
}
