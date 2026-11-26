package com.pitgpt.galaxy.data.remote

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HttpLilaMemoryClientTest {
    @Test
    fun blankSearchLoadsRecentMemoriesWithoutSendingAQuery() = runBlocking {
        var observedMethod = ""
        var observedPath = ""
        var observedBody: JSONObject? = JSONObject().put("unexpected", true)
        val client = HttpLilaMemoryClient { method, path, body ->
            observedMethod = method
            observedPath = path
            observedBody = body
            JSONObject().put(
                "memories",
                JSONArray().put(
                    JSONObject()
                        .put("memory_id", "memory-one")
                        .put("content", "Enjoys astronomy")
                )
            )
        }

        val memories = client.search()

        assertEquals("GET", observedMethod)
        assertEquals("/v1/lila/memories/search?limit=20", observedPath)
        assertNull(observedBody)
        assertEquals("memory-one", memories.single().memoryId)
        assertEquals("Enjoys astronomy", memories.single().content)
    }

    @Test
    fun searchEncodesQueryAndHonorsBoundedLimit() = runBlocking {
        var observedPath = ""
        val client = HttpLilaMemoryClient { _, path, _ ->
            observedPath = path
            JSONObject().put("memories", JSONArray())
        }

        client.search("space nebula", limit = 7)

        assertEquals(
            "/v1/lila/memories/search?limit=7&query=space%20nebula",
            observedPath
        )
    }

    @Test
    fun saveAndDeleteUseOwnerAuthenticatedTransportContract() = runBlocking {
        var savePath = ""
        var saveBody: JSONObject? = null
        var deletePath = ""
        val client = HttpLilaMemoryClient { method, path, body ->
            when (method) {
                "PUT" -> {
                    savePath = path
                    saveBody = body
                    JSONObject().put("memory_id", "memory-one").put("saved", true)
                }
                "DELETE" -> {
                    deletePath = path
                    JSONObject().put("memory_id", "memory-one").put("deleted", true)
                }
                else -> error("Unexpected method")
            }
        }

        val saved = client.save("memory-one", "Enjoys astronomy")
        val deleted = client.delete("memory-one")

        assertEquals("/v1/lila/memories/memory-one", savePath)
        assertEquals("Enjoys astronomy", saveBody?.getString("content"))
        assertEquals("memory-one", saved.memoryId)
        assertEquals("Enjoys astronomy", saved.content)
        assertEquals("/v1/lila/memories/memory-one", deletePath)
        assertTrue(deleted)
    }

    @Test
    fun invalidContentIsRejectedBeforeCallingTransport() = runBlocking {
        var called = false
        val client = HttpLilaMemoryClient { _, _, _ ->
            called = true
            JSONObject()
        }

        val rejected = runCatching { client.save("memory-one", "   ") }.isFailure

        assertTrue(rejected)
        assertFalse(called)
    }
}
