package com.apprefer.sdk

import com.apprefer.sdk.internal.EventQueue
import com.apprefer.sdk.internal.JsonCodec
import com.apprefer.sdk.support.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences

class SDK090Test {
    private class QueueHarness {
        var disk: String? = null
        var status: Int? = null
        var enabled = true
        var clock = 1000000000L
        val calls = mutableListOf<Map<String, Any?>>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        fun queue() = EventQueue({ disk }, { disk = it }, { enabled }, { calls.add(it); status }, scope, { clock })
    }
    @Test fun survivesRestartWithStableEventIdAndBackoff() = runBlocking {
        val h = QueueHarness(); val first = h.queue()
        assertEquals(AppReferEventResult.QUEUED, first.enqueue(mapOf("event_name" to "offline")))
        val before = JsonCodec.decodeObject(h.disk).objects("events").single()
        assertEquals(1, (before["attempts"] as Number).toInt())
        assertTrue((before["next"] as Number).toLong() > h.clock)
        h.clock += 3000; h.status = 200
        h.queue().flush()
        assertEquals(h.calls[0]["eventId"], h.calls[1]["eventId"])
        assertTrue(JsonCodec.decodeObject(h.disk).objects("events").isEmpty())
        first.clear(); h.scope.cancel()
    }
    @Test fun drops4xxRetries5xxAndNetworkClearsAndDisables() = runBlocking {
        val h = QueueHarness(); val queue = h.queue()
        h.status = 422
        assertEquals(AppReferEventResult.DROPPED, queue.enqueue(emptyMap()))
        h.status = 503
        assertEquals(AppReferEventResult.QUEUED, queue.enqueue(emptyMap()))
        queue.clear(); assertNull(h.disk)
        h.status = null; assertEquals(AppReferEventResult.QUEUED, queue.enqueue(emptyMap()))
        h.enabled = false; queue.flush(); assertNull(h.disk)
        h.scope.cancel()
    }
    @Test fun boundsAndExpiresOldestEvents() = runBlocking {
        val h = QueueHarness()
        h.disk = JsonCodec.encode(mapOf("events" to (0..1002).map {
            mapOf("id" to "$it", "body" to emptyMap<String, Any>(), "created" to if (it == 1002) 0L else h.clock, "next" to h.clock + 10000, "attempts" to 0)
        }))
        val queue = h.queue(); queue.flush()
        val saved = JsonCodec.decodeObject(h.disk).objects("events")
        assertEquals(1000, saved.size); assertEquals("2", saved.first()["id"])
        queue.clear(); h.scope.cancel()
    }
    @Test fun lateEventCannotRepopulateClearedQueue() = runBlocking {
        var disk: String? = null
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Int?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val queue = EventQueue({ disk }, { disk = it }, { true }, { entered.complete(Unit); finish.await() }, scope)
        val sending = async { queue.enqueue(emptyMap()) }
        entered.await(); queue.clear(); finish.complete(503)
        assertEquals(AppReferEventResult.DROPPED, sending.await())
        assertNull(disk); scope.cancel()
    }
    @Test fun twoAccountsUseNewTokenAndOldConversationsDisappear() = runBlocking {
        val store = object : SupportStore { override var token: String? = null; override var profile: String? = null }
        var user = "A"; var minted = 0
        val tokens = mutableListOf<String?>(); val paths = mutableListOf<String>()
        val api = SupportAPI({ "pk_support" }, store, { mapOf("appUserId" to user) }, { emptyMap() },
            SupportTransport { method, url, headers, _ ->
                paths.add(url)
                when {
                    method == "DELETE" -> { tokens.add(headers["X-AppRefer-Support-Session"]); SupportResponse(200, "{}") }
                    url.endsWith("/session") -> SupportResponse(201, """{"session":"s${++minted}"}""")
                    else -> { tokens.add(headers["X-AppRefer-Support-Session"]); SupportResponse(200, if (minted == 1) """{"conversations":[{"id":"A-private"}]}""" else """{"conversations":[]}""") }
                }
            })
        assertEquals(1, api.conversations().size)
        api.reset(); assertNull(store.token); assertNull(store.profile)
        user = "B"; assertTrue(api.conversations().isEmpty())
        withTimeout(1000) { while (tokens.size < 3) delay(1) }
        assertEquals(2, tokens.count { it == "s1" }); assertEquals(1, tokens.count { it == "s2" })
        assertTrue(paths.all { it.contains("/api/support/v1/") })
        assertEquals("s2", store.token)
        api.reset()
    }
    @Test fun typedOrganicRequiresServerConfirmationAndConfirmedCacheWins() {
        assertEquals(AppReferAttributionResult.Organic, AppReferAttributionResult.resolve(null, true, false))
        assertTrue(AppReferAttributionResult.resolve(null, false, false) is AppReferAttributionResult.Failed)
        assertEquals(AppReferAttributionResult.Organic, AppReferAttributionResult.resolve(null, true, true))
    }
    @Test fun supportOnlyDoesNotInitializeAttributionOrCollectDeviceSignals() {
        val requestedPrefs = mutableListOf<String>()
        val prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, _ ->
            when (method.name) { "getString" -> null; "getBoolean" -> false; else -> null }
        } as SharedPreferences
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences { requestedPrefs.add(name); return prefs }
        }
        AppRefer.support.configure(context, "pk_support_only")
        assertNull(AppRefer.currentApiKey())
        assertNull(AppRefer.getDeviceId())
        assertTrue(requestedPrefs.isEmpty())
    }
}
