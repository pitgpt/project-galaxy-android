package com.pitgpt.galaxy.data.remote

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch

/**
 * A deterministic connection that blocks in responseCode until disconnect().
 * Used to prove coroutine cancellation closes an in-flight socket operation.
 */
internal class BlockingHttpURLConnection(url: URL) : HttpURLConnection(url) {
    private val monitor = java.lang.Object()
    private val requestBody = ByteArrayOutputStream()

    val responseCodeStarted = CountDownLatch(1)

    @Volatile
    var disconnected: Boolean = false
        private set

    override fun connect() = Unit

    override fun disconnect() {
        synchronized(monitor) {
            disconnected = true
            monitor.notifyAll()
        }
    }

    override fun usingProxy(): Boolean = false

    override fun getOutputStream(): OutputStream = requestBody

    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))

    override fun getResponseCode(): Int {
        responseCodeStarted.countDown()
        synchronized(monitor) {
            while (!disconnected) {
                monitor.wait()
            }
        }
        throw IOException("Connection was disconnected")
    }
}
