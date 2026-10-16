package com.apprefer.sdk

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.apprefer.sdk.internal.*
import com.apprefer.sdk.support.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class SDK091Test {
    private class Fixture {
        val data = mutableMapOf<String, Any?>()
        val directory = java.nio.file.Files.createTempDirectory("ar091").toFile()
        val prefs: SharedPreferences
        init {
            lateinit var editor: SharedPreferences.Editor
            editor = Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader, arrayOf(SharedPreferences.Editor::class.java)) { _, method, args ->
                when {
                    method.name.startsWith("put") -> { data[args!![0] as String] = args[1]; editor }
                    method.name == "remove" -> { data.remove(args!![0]); editor }
                    method.name == "clear" -> { data.clear(); editor }
                    method.name == "commit" -> true
                    else -> null
                }
            } as SharedPreferences.Editor
            prefs = Proxy.newProxyInstance(SharedPreferences::class.java.classLoader, arrayOf(SharedPreferences::class.java)) { _, method, args ->
                when (method.name) { "edit" -> editor; "getAll" -> data.toMap(); "getString", "getBoolean" -> data[args!![0]] ?: args[1]; else -> null }
            } as SharedPreferences
        }
        val context = object : ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int) = prefs
            override fun getNoBackupFilesDir() = directory
            override fun getCacheDir() = directory
        }
        val storage = AppReferStorage(context)
        val logger = Logger(false, 1)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val calls = java.util.concurrent.CopyOnWriteArrayList<Map<String, Any?>>()
        var status: Int? = 429
        var clock = System.currentTimeMillis()
        val paths = java.util.concurrent.CopyOnWriteArrayList<String>()
        val http = HttpClient("pk_test", logger, collectionAllowed = storage::isSdkEnabled, transport = { _, path, _ -> paths.add(path); mapOf("attribution" to null) })
        fun queue() = EventQueue({ storage.pendingEvents }, { storage.pendingEvents = it }, storage::isSdkEnabled, { calls.add(it); status }, scope, { clock })
        val stateField = AppRefer::class.java.getDeclaredField("state").apply { isAccessible = true }
        val queueField = AppRefer::class.java.getDeclaredField("eventQueue").apply { isAccessible = true }
        val oldState = stateField.get(AppRefer)
        val oldQueue = queueField.get(AppRefer)
        val oldContext = AppReferContext.context
        val queue = queue()
        init {
            storage.setDeviceId("device"); storage.setMatchRequestAttempted(true)
            storage.setTrackingConsent("denied"); storage.setReportedConsent("denied")
            storage.setAttributionCache("""{"network":"meta","matchType":"click"}""")
            val type = Class.forName("com.apprefer.sdk.AppRefer\$SdkState")
            val ctor = type.declaredConstructors.single().apply { isAccessible = true }
            val state = ctor.newInstance("pk_test", false, 1, storage, logger, http, DeviceIdManager(context, storage, logger), DeviceInfo(context, logger), InstallReferrerCollector(context, logger), context)
            type.getDeclaredMethod("setEvents", EventQueue::class.java).invoke(state, queue)
            stateField.set(AppRefer, state); queueField.set(AppRefer, queue); AppReferContext.context = context
        }
        suspend fun close() { queue.clear(); scope.cancel(); stateField.set(AppRefer, oldState); queueField.set(AppRefer, oldQueue); AppReferContext.context = oldContext; directory.deleteRecursively() }
    }
    @Test fun publicEventIdentityPersistsThroughSwitchRestartAndRetry() = runBlocking {
        val f = Fixture()
        try {
            AppRefer.setUserId("A")
            assertEquals(AppReferEventResult.QUEUED, AppRefer.trackEventResult("signup"))
            val saved = JsonCodec.decodeObject(f.storage.pendingEvents).objects("events").single()
            assertEquals("A", (saved["body"] as Map<*, *>)["app_user_id"])
            AppRefer.setUserId("B"); f.clock += 3000; f.status = 200
            val restarted = f.queue(); restarted.flush()
            assertEquals(2, f.calls.size)
            assertTrue(f.calls.all { it["app_user_id"] == "A" && it["eventId"] == saved["id"] })
            restarted.clear()
        } finally { f.close() }
    }
    @Test fun anonymousAndInternalEventsOmitIdentity() = runBlocking {
        val f = Fixture()
        try {
            f.status = 200; AppRefer.trackEvent("anonymous"); AppRefer.setUserId("A"); AppRefer.trackEvent("_internal")
            assertEquals(2, f.calls.size); assertTrue(f.calls.none { it.containsKey("app_user_id") })
        } finally { f.close() }
    }
    @Test fun publicDisableBlocksConfigureConsentAndEventsUntilNextConfigure() = runBlocking {
        val f = Fixture()
        try {
            AppRefer.trackEvent("queued"); AppRefer.setCollectionEnabled(false)
            assertNull(f.storage.pendingEvents)
            assertEquals("meta", AppRefer.configure(f.context, "pk_test")?.network)
            assertEquals(AppReferEventResult.DROPPED, AppRefer.trackEventResult("blocked"))
            AppRefer.setTrackingConsent(true); AppRefer.trackingConsentDidChange(); AppRefer.updateConversionValue()
            delay(50); assertTrue(f.paths.isEmpty())
            assertFalse(AppReferStorage(f.context).isCollectionEnabled())
            AppRefer.setCollectionEnabled(true)
            assertEquals(AppReferEventResult.DROPPED, AppRefer.trackEventResult("still-blocked"))
            f.status = 200
            AppRefer.configure(f.context, "pk_test")
            assertEquals(AppReferEventResult.SENT, AppRefer.trackEventResult("resumed"))
        } finally { f.close() }
    }
    @Test fun disabledChoiceSurvivesNewStorageAndResetAndNoCacheFails() = runBlocking {
        val f = Fixture()
        try {
            AppRefer.setCollectionEnabled(false)
            val next = AppReferStorage(f.context)
            assertFalse(next.isSdkEnabled()); next.clearAttributionCache(); next.organicConfirmed = false
            assertTrue(AppRefer.configureResult(f.context, "pk_test") is AppReferAttributionResult.Failed)
            // Reset preserves the choice; test storage reset operation without initializing optional Support UI.
            next.clearUser(); assertFalse(next.isCollectionEnabled())
            assertTrue(f.paths.isEmpty())
        } finally { f.close() }
    }
    @Test fun transportDropsLateConsentResponseAndRejectsNewRequestsWhenPaused() = runBlocking {
        val f = Fixture()
        try {
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            var calls = 0
            val http = HttpClient("pk", f.logger, collectionAllowed = f.storage::isSdkEnabled, transport = { _, _, _ -> calls++; entered.complete(Unit); release.await(); emptyMap() })
            val sending = async { http.post("/api/track/configure", emptyMap()) }
            entered.await(); f.storage.setCollectionEnabled(false); http.cancel(); release.complete(Unit)
            assertNull(sending.await())
            assertNull(http.post("/api/track/configure", emptyMap())); assertNull(http.post("/api/track/conversion-value", emptyMap())); assertNull(http.event(emptyMap()))
            assertEquals(1, calls)
        } finally { f.close() }
    }
    @Test fun supportContinuesWhileCollectionDisabled() = runBlocking {
        val f = Fixture()
        try {
            AppRefer.setCollectionEnabled(false)
            val store = object : SupportStore { override var token: String? = null; override var profile: String? = null }
            val paths = mutableListOf<String>()
            val api = SupportAPI({ "pk_test" }, store, { emptyMap() }, { emptyMap() }, SupportTransport { _, path, _, _ -> paths.add(path); SupportResponse(200, if (path.endsWith("/session")) """{"session":"support"}""" else """{"conversations":[]}""") })
            assertTrue(api.conversations().isEmpty()); assertTrue(paths.isNotEmpty()); assertTrue(paths.all { it.contains("/support/v1/") })
        } finally { f.close() }
    }
    @Test fun disableCancelsActualConfigureAndEventConnections() = runBlocking {
        for (event in listOf(false, true)) {
            val f = Fixture()
            val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
            val accepted = CompletableDeferred<Unit>()
            val release = java.util.concurrent.CountDownLatch(1)
            val serving = launch(Dispatchers.IO) {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader()
                    var length = 0
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.startsWith("Content-Length:", ignoreCase = true)) length = line.substringAfter(':').trim().toInt()
                        if (line.isEmpty()) break
                    }
                    repeat(length) { input.read() }
                    accepted.complete(Unit)
                    release.await(3, java.util.concurrent.TimeUnit.SECONDS)
                }
            }
            try {
                val client = HttpClient("pk", f.logger, "http://127.0.0.1:${server.localPort}", null, collectionAllowed = f.storage::isSdkEnabled)
                val sending = async(Dispatchers.IO) { if (event) client.event(emptyMap()) else client.post("/api/track/configure", emptyMap()) }
                withTimeout(2000) { accepted.await() }
                f.storage.setCollectionEnabled(false)
                val started = System.nanoTime(); client.cancel()
                assertTrue("Cancellation must not wait for the response timeout", (System.nanoTime() - started) / 1_000_000 < 1000)
                assertNull(withTimeout(2000) { sending.await() })
            } finally { release.countDown(); server.close(); serving.join(); f.close() }
        }
    }

}
