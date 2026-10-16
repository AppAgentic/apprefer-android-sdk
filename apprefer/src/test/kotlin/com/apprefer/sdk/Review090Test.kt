package com.apprefer.sdk

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.apprefer.sdk.internal.*
import com.apprefer.sdk.models.Attribution
import com.apprefer.sdk.support.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class Review090Test {
    @Test fun embeddedSupportReRegistersOnEveryAttachAndRebuildsAfterDetachedReset() {
        var generation = 0
        val listeners = mutableSetOf<() -> Unit>()
        var rebuilds = 0
        val lifecycle = SupportAccountLifecycle({ generation }, listeners) { rebuilds++ }
        lifecycle.attach(); assertEquals(1, listeners.size)
        lifecycle.detach(); assertTrue(listeners.isEmpty())
        generation++
        lifecycle.attach(); assertEquals(1, rebuilds); assertEquals(1, listeners.size)
        generation++; listeners.toList().forEach { it() }; assertEquals(2, rebuilds)
        lifecycle.detach(); lifecycle.attach(); assertEquals(2, rebuilds)
        generation++; listeners.toList().forEach { it() }; assertEquals(3, rebuilds)
        lifecycle.detach()
    }
    @Test fun localResetDoesNotWaitForRevocationOrHoldTheSessionMutex() = runBlocking {
        val store = object : SupportStore { override var token: String? = "A"; override var profile: String? = "A" }
        val entered = java.util.concurrent.CountDownLatch(1); val finish = java.util.concurrent.CountDownLatch(1)
        val api = SupportAPI({ "pk_test" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { method, url, _, _ ->
            if (method == "DELETE") { entered.countDown(); finish.await(2, java.util.concurrent.TimeUnit.SECONDS); SupportResponse(200, "{}") }
            else SupportResponse(200, if (url.endsWith("/session")) """{"session":"B"}""" else """{"conversations":[]}""")
        })
        try {
            withTimeout(1000) { api.reset() }
            assertNull(store.token)
            assertTrue(withContext(Dispatchers.IO) { entered.await(1, java.util.concurrent.TimeUnit.SECONDS) })
            withTimeout(1000) { api.conversations() }; assertEquals("B", store.token)
        } finally { finish.countDown() }
    }
    @Test fun pendingAttIsPreservedInCacheAndConfirmedCacheBeatsFailure() {
        val pending = JsonCodec.parseAttribution(mapOf("network" to "organic", "trackingDecisionPending" to true))!!
        val cached = JsonCodec.parseAttribution(JsonCodec.decodeObject(JsonCodec.encodeAttribution(pending)))!!
        assertEquals(AppReferAttributionResult.Pending, AppReferAttributionResult.resolve(cached, true, true))
        assertTrue(AppReferAttributionResult.resolve(Attribution("meta", matchType = "click"), false, true) is AppReferAttributionResult.Attributed)
    }
    @Test fun rateLimitAndTimeoutPauseBacklogWithRetryAfter() = runBlocking {
        for (status in listOf(408, 429, 503)) {
            val now = 1000000000L
            var disk: String? = JsonCodec.encode(mapOf("events" to (0..2).map { mapOf("id" to "$it", "body" to emptyMap<String, Any>(), "created" to now, "next" to 0, "attempts" to 0) }))
            var calls = 0
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val q = EventQueue({ disk }, { disk = it }, { true }, { calls++; EventResponse(status, 60000) }, scope, { now })
            q.flush(); assertEquals(1, calls)
            val saved = JsonCodec.decodeObject(disk).objects("events")
            assertEquals(3, saved.size); assertTrue(saved.all { (it["next"] as Number).toLong() >= now + 60000 })
            q.clear(); scope.cancel()
        }
        assertEquals(90000L, HttpClient.retryAfter("90"))
        assertEquals(0L, HttpClient.retryAfter("broken"))
    }
    @Test fun awaitedTrackOnlySendsItsEventAndQueueIoIsOffCallerThread() = runBlocking {
        val now = System.currentTimeMillis()
        var disk: String? = JsonCodec.encode(mapOf("events" to (0..2).map { mapOf("id" to "$it", "body" to emptyMap<String, Any>(), "created" to now, "next" to 0, "attempts" to 0) }))
        val caller = Thread.currentThread(); var calls = 0
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val q = EventQueue({ assertNotSame(caller, Thread.currentThread()); disk }, { assertNotSame(caller, Thread.currentThread()); disk = it }, { true }, { calls++; 200 }, scope)
        assertEquals(AppReferEventResult.SENT, q.enqueue(emptyMap())); assertEquals(1, calls)
        q.clear(); scope.cancel()
    }
    @Test fun corruptQueueQuarantinesAndWriteFailuresNeverEscape() = runBlocking {
        var disk: String? = "bad json"; var bad: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val q = EventQueue({ disk }, { disk = it }, { true }, { 200 }, scope, quarantine = { bad = it })
        assertEquals(AppReferEventResult.SENT, q.enqueue(emptyMap())); assertEquals("bad json", bad)
        val failing = EventQueue({ null }, { throw java.io.IOException("disk") }, { true }, { 200 }, scope)
        assertEquals(AppReferEventResult.DROPPED, failing.enqueue(emptyMap()))
        failing.clear(); q.clear(); scope.cancel()
    }
    @Test fun resetAndReplaceReleaseAnOldDrainWithoutLosingNewQueueWrites() = runBlocking {
        var disk: String? = null
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Int?>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val q = EventQueue({ disk }, { disk = it }, { true }, { entered.complete(Unit); finish.await() }, scope)
        val a = async { q.enqueue(mapOf("user" to "A")) }; entered.await()
        q.clear()
        q.replace({ disk }, { disk = it }, { true }, { 200 }, {})
        assertEquals(AppReferEventResult.SENT, q.enqueue(mapOf("user" to "B")))
        finish.complete(503); assertEquals(AppReferEventResult.DROPPED, a.await())
        assertTrue(JsonCodec.decodeObject(disk).objects("events").isEmpty()); q.clear(); scope.cancel()
    }
    @Test fun composedBeforeResetCannotUploadOrSendAfterwardAndUnknownKeyRevokesLater() = runBlocking {
        val store = object : SupportStore { override var token: String? = "A"; override var profile: String? = "A" }
        var key: String? = null
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val api = SupportAPI({ key }, store, { emptyMap() }, { emptyMap() }, SupportTransport { method, url, _, _ -> paths.add(method + url); SupportResponse(200, if (url.endsWith("/session")) """{"session":"B"}""" else """{"conversations":[]}""") })
        val epoch = api.actionGeneration; api.reset(); assertNull(store.token); assertNotNull(store.pendingRevocations)
        key = "pk_test"
        for (work in listOf<suspend () -> Unit>({ api.upload(byteArrayOf(), epoch); Unit }, { api.start("A", emptyList(), "12345678", generation = epoch); Unit }, { api.send("thread", "A", emptyList(), "12345678", epoch); Unit })) {
            assertTrue(runCatching { work() }.isFailure)
        }
        api.conversations()
        withTimeout(1000) { while (paths.none { it.startsWith("DELETE") }) delay(1) }
        assertTrue(paths.none { it.contains("attachments") || it.contains("/messages") })
    }
    @Test fun publicResetBeforeConfigureClearsPersistedIdentityEventsAndSupportSlots() = runBlocking {
        val maps = mutableMapOf<String, MutableMap<String, Any?>>()
        var canCommit = true
        fun preferences(name: String): SharedPreferences {
            val data = maps.getOrPut(name) { mutableMapOf() }
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when {
                    method.name.startsWith("put") -> { data[args!![0] as String] = args[1]; editor }
                    method.name == "remove" -> { data.remove(args!![0]); editor }
                    method.name == "clear" -> { data.clear(); editor }
                    method.name == "commit" -> canCommit
                    else -> null
                }
            } as SharedPreferences.Editor
            return Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) { "edit" -> editor; "getAll" -> data.toMap(); "getString" -> data[args!![0]] ?: args[1]; "getBoolean" -> data[args!![0]] ?: args[1]; else -> null }
            } as SharedPreferences
        }
        val directory = java.nio.file.Files.createTempDirectory("ar-reset").toFile()
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences(name)
            override fun getNoBackupFilesDir() = directory
            override fun getCacheDir() = directory
        }
        val appField = AppReferSupport::class.java.getDeclaredField("app").apply { isAccessible = true }
        val clientField = AppReferSupport::class.java.getDeclaredField("client").apply { isAccessible = true }
        val oldApp = appField.get(AppReferSupport); val oldClient = clientField.get(AppReferSupport)
        val oldContext = AppReferContext.context
        try {
            appField.set(AppReferSupport, null); clientField.set(AppReferSupport, null); AppReferContext.context = context
            AppReferStorage(context).setUserId("A"); AppReferStorage(context).pendingEvents = "A events"
            preferences("apprefer_support").edit().putString("profile", "A").putString("pushToken", "A").commit()
            java.io.File(directory, "apprefer-support-session").writeText("persisted A token")
            canCommit = false // A failed queue write must not skip Support cleanup or escape.
            AppRefer.reset()
            assertNull(AppReferStorage(context).getUserId()); assertNull(AppReferStorage(context).pendingEvents)
            assertFalse(java.io.File(directory, "apprefer-support-session").exists())
            assertNull(preferences("apprefer_support").getString("profile", null)); assertNull(preferences("apprefer_support").getString("pushToken", null))
            AppRefer.support.setUserId("B"); assertEquals("B", AppReferStorage(context).getUserId())
        } finally { appField.set(AppReferSupport, oldApp); clientField.set(AppReferSupport, oldClient); AppReferContext.context = oldContext; directory.deleteRecursively() }
    }
}
