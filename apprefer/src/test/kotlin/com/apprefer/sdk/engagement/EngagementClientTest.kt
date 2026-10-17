package com.apprefer.sdk.engagement

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Lifecycle engagement client against an in-memory stand-in for AppRefer's engagement routes. */
class EngagementClientTest {
    data class Call(val method: String, val path: String, val headers: Map<String, String>, val body: Map<String, Any?>?)

    internal class Server {
        data class Install(val secret: String, val key: String, var epoch: Int = 0, var account: String? = null,
                           val prefs: MutableMap<String, Boolean> = mutableMapOf("marketing" to false, "reminders" to false, "service" to true),
                           val patches: MutableList<Map<String, Any?>> = mutableListOf())
        val installs = ConcurrentHashMap<String, Install>()
        val calls = CopyOnWriteArrayList<Call>()
        @Volatile var offline = false
        @Volatile var disabled = false
        val delays = ConcurrentHashMap<String, Long>()
        @Volatile var receiptStatus = "accepted"
        private var counter = 0

        val transport: EngagementTransport = { method, path, headers, body ->
            delays["$method $path"]?.let { delay(it) }
            calls.add(Call(method, path, headers, body))
            if (offline) null to null else handle(method, path, headers, body ?: emptyMap())
        }

        private fun err(code: Int, c: String) = code to mapOf<String, Any?>("error" to mapOf("code" to c))

        @Synchronized private fun handle(method: String, path: String, headers: Map<String, String>, body: Map<String, Any?>): Pair<Int?, Map<String, Any?>?> {
            if (disabled) return err(404, "ENGAGEMENT_DISABLED")
            val key = headers["X-AppRefer-Key"] ?: ""
            if (method == "POST" && path == "/api/engagement/v1/installs") {
                counter++
                val id = "ei_" + "a".repeat(25) + "234567"[counter % 6]
                val secret = "eis_" + "S".repeat(42) + (counter % 10)
                installs[id] = Install(secret, key)
                return 201 to mapOf("installId" to id, "installSecret" to secret)
            }
            val cred = headers["X-AppRefer-Install"] ?: return err(401, "INVALID_INSTALL")
            val id = cred.substringBefore("."); val inst = installs[id]
            if (inst == null || inst.secret != cred.substringAfter(".") || inst.key != key) return err(401, "INVALID_INSTALL")
            fun me() = mapOf<String, Any?>("install" to mapOf("installId" to id, "accountEpoch" to inst.epoch))
            fun prefs() = mapOf<String, Any?>("purposes" to inst.prefs.mapValues { mapOf("granted" to it.value) }, "accountEpoch" to inst.epoch)
            // Identity mutations must state the epoch they were made under (web/lib/engagement/identity.ts).
            val mutation = "$method $path" in setOf("PUT /api/engagement/v1/installs/me/preferences", "POST /api/engagement/v1/installs/me/logout", "POST /api/engagement/v1/installs/me/account")
            val expected = (body["expectedEpoch"] as? Number)?.toInt()
            if (mutation && expected == null) return err(400, "INVALID_BODY")
            val stale = err(409, "IDENTITY_CHANGED")
            return when ("$method $path") {
                "GET /api/engagement/v1/installs/me" -> 200 to me()
                "PATCH /api/engagement/v1/installs/me" -> { inst.patches.add(body); 200 to me() }
                "DELETE /api/engagement/v1/installs/me" -> { installs.remove(id); 200 to mapOf("deleted" to true) }
                "POST /api/engagement/v1/installs/me/account" -> {
                    val a = body["assertion"] as? String
                    if (a == null || !a.startsWith("ok:")) return err(401, "INVALID_ASSERTION")
                    val user = a.removePrefix("ok:")
                    if (inst.account != user && expected != inst.epoch) return stale
                    if (inst.account != user) { inst.account = user; inst.epoch++; inst.prefs["marketing"] = false; inst.prefs["reminders"] = false }
                    200 to mapOf("accountEpoch" to inst.epoch)
                }
                "POST /api/engagement/v1/installs/me/logout" -> { if (expected != inst.epoch) return stale; inst.account = null; inst.epoch++; inst.prefs["marketing"] = false; inst.prefs["reminders"] = false; 200 to mapOf("accountEpoch" to inst.epoch) }
                "GET /api/engagement/v1/installs/me/preferences" -> 200 to prefs()
                "PUT /api/engagement/v1/installs/me/preferences" -> { if (expected != inst.epoch) return stale; body.forEach { (k, v) -> if (v is Boolean) inst.prefs[k] = v }; 200 to prefs() }
                "POST /api/engagement/v1/installs/me/receipts" -> {
                    @Suppress("UNCHECKED_CAST") val items = body["receipts"] as List<Map<String, Any?>>
                    200 to mapOf("results" to items.map { mapOf("messageId" to it["messageId"], "status" to receiptStatus) })
                }
                else -> err(404, "NOT_FOUND")
            }
        }
        fun count(method: String, path: String) = calls.count { it.method == method && it.path == path }
        /** The app's backend binds (null: detaches) directly; the SDK isn't told. */
        fun backendBind(id: String, account: String?) { installs[id]!!.apply { this.account = account; epoch++; prefs["marketing"] = false; prefs["reminders"] = false } }
    }

    internal class MemPrefs : EngagementPrefs {
        val map = ConcurrentHashMap<String, String>()
        override fun getString(key: String) = map[key]
        override fun putString(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    internal class MemCredentials : EngagementCredentialStore {
        val map = ConcurrentHashMap<String, String>()
        @Volatile var locked = false
        @Volatile var failWrites = false
        override fun read(slot: String) = if (locked) EngagementCredentialStore.Read.Unavailable else map[slot]?.let { EngagementCredentialStore.Read.Found(it) } ?: EngagementCredentialStore.Read.Missing
        override fun write(slot: String, value: String): Boolean { if (failWrites) return false; map[slot] = value; return true }
        override fun delete(slot: String) { map.remove(slot) }
    }

    private val server = Server()
    private val creds = MemCredentials()
    private val prefs = MemPrefs()
    private val identity = CopyOnWriteArrayList<Pair<String?, Int>>()
    private val key = "pk_live_sparkly000000000000000001"

    private fun client(now: () -> Long = { System.currentTimeMillis() }) = EngagementClient(creds, prefs, server.transport, now,
        deviceState = { EngagementDeviceState("Europe/London", "en-GB", true, "granted") }, deviceId = { "dev-0000-0001" },
        identitySink = { iid, epoch -> identity.add(iid to epoch) })
    private fun id(s: AppReferEngagementStatus) = (s as? AppReferEngagementStatus.Active)?.installId

    @Test fun registersOnceAndKeepsTheSecretOnlyInTheCredentialStore() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions(allowedImageHosts = setOf("assets.example.com"))))!!
        assertEquals(1, server.count("POST", "/api/engagement/v1/installs"))
        val reg = server.calls.first { it.path == "/api/engagement/v1/installs" }
        assertNull(reg.headers["X-AppRefer-Install"])
        assertEquals("android", reg.body!!["platform"])
        val secret = server.installs[installId]!!.secret
        assertTrue(creds.map.values.single().contains(secret))
        assertFalse(prefs.map.values.any { it.contains(secret) })
        val patch = server.installs[installId]!!.patches.last()
        assertEquals("granted", patch["notificationPermission"])
        assertEquals(true, (patch["capabilities"] as Map<*, *>)["images"])
        assertEquals(installId to 0, identity.last())
        // Another process with the same store reuses it.
        assertEquals(installId, id(client().start(key, true, AppReferEngagementOptions())))
        assertEquals(1, server.count("POST", "/api/engagement/v1/installs"))
    }

    @Test fun unchangedStateIsNotResentAndTheTokenIsSentOnce() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        c.setPushToken("fcm:" + "x".repeat(40)); c.setPushToken("fcm:" + "x".repeat(40))
        assertEquals(2, server.installs[installId]!!.patches.size)
        assertEquals("fcm:" + "x".repeat(40), server.installs[installId]!!.patches.last()["pushToken"])
    }

    @Test fun disabledOnTheServerDegradesQuietlyForSixHours() = runBlocking {
        server.disabled = true
        val c = client()
        assertEquals(AppReferEngagementStatus.Disabled, c.start(key, true, AppReferEngagementOptions()))
        assertTrue(creds.map.isEmpty())
        server.disabled = false
        assertEquals(AppReferEngagementStatus.Disabled, client().start(key, true, AppReferEngagementOptions()))
        assertEquals(1, server.calls.size)
        try { c.preferences(); fail() } catch (e: AppReferEngagementException) { assertEquals(AppReferEngagementException.Reason.NOT_STARTED, e.reason) }
        val later = System.currentTimeMillis() + 6 * 3600_000L + 1
        assertNotNull(id(client { later }.start(key, true, AppReferEngagementOptions())))
    }

    @Test fun lockedStoreAndOfflineArePendingThenRecover() = runBlocking {
        creds.locked = true
        val c = client()
        assertEquals(AppReferEngagementStatus.Pending, c.start(key, true, AppReferEngagementOptions()))
        assertTrue(server.calls.isEmpty())
        creds.locked = false; server.offline = true
        c.becameActive(); assertEquals(AppReferEngagementStatus.Pending, c.status)
        server.offline = false
        c.becameActive(); assertNotNull(id(c.status))
        assertEquals(1, server.installs.size)
    }

    @Test fun anUnsaveableCredentialLeavesNoInstallBehind() = runBlocking {
        creds.failWrites = true
        assertEquals(AppReferEngagementStatus.Pending, client().start(key, true, AppReferEngagementOptions()))
        assertTrue(server.installs.isEmpty())
    }

    @Test fun anErasedInstallIsReplacedOnce() = runBlocking {
        val first = id(client().start(key, true, AppReferEngagementOptions()))!!
        server.installs.remove(first)
        val second = id(client().start(key, true, AppReferEngagementOptions()))!!
        assertNotEquals(first, second)
    }

    @Test fun configurationSwitchUsesItsOwnSlot() = runBlocking {
        val c = client()
        val a = id(c.start(key, false, AppReferEngagementOptions()))!!
        c.configurationChanged("pk_test_sparkly000000000000000001")
        val b = id(c.status)!!
        assertNotEquals(a, b)
        assertEquals("pk_test_sparkly000000000000000001", server.installs[b]!!.key)
        assertEquals(2, creds.map.size)
    }

    @Test fun signInLogoutSignInRunInCallOrderWhileInFlight() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        server.delays["POST /api/engagement/v1/installs/me/account"] = 200
        val a = async { c.identify("ok:user-a") }
        delay(30)
        val out = async { c.logout() }
        delay(10)
        val b = async { c.identify("ok:user-b") }
        a.await(); out.await(); b.await()
        val order = server.calls.filter { it.path.endsWith("/account") || it.path.endsWith("/logout") }.map { if (it.path.endsWith("/logout")) "logout" else it.body!!["assertion"] }
        assertEquals(listOf("ok:user-a", "logout", "ok:user-b"), order)
        assertEquals("user-b", server.installs[installId]!!.account)
        assertEquals(server.installs[installId]!!.epoch, c.stateForTesting().first)
    }

    @Test fun logoutDuringRegistrationIsSentAfterIt() = runBlocking {
        server.delays["POST /api/engagement/v1/installs"] = 200
        val c = client()
        val started = async { c.start(key, true, AppReferEngagementOptions()) }
        delay(30)
        c.logout()
        val installId = id(started.await())!!
        assertEquals(1, server.count("POST", "/api/engagement/v1/installs/me/logout"))
        assertEquals(1, server.installs[installId]!!.epoch)
    }

    @Test fun offlineLogoutIsSentFirstNextTimeAndBlocksMatching() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        c.identify("ok:user-a")
        server.offline = true
        c.logout()
        assertTrue(c.stateForTesting().third)
        assertNull(c.snapshot.get()!!.accountEpoch)
        assertEquals(installId to -1, identity.last())
        server.offline = false
        client().start(key, true, AppReferEngagementOptions())
        assertNull(server.installs[installId]!!.account)
    }

    @Test fun resetBeforeStartIsAppliedWhenEngagementStarts() = runBlocking {
        val installId = id(client().start(key, true, AppReferEngagementOptions()))!!
        client().let { it.start(key, true, AppReferEngagementOptions()); it.identify("ok:user-a") }
        val next = client()
        next.logout()
        assertEquals("user-a", server.installs[installId]!!.account)
        next.start(key, true, AppReferEngagementOptions())
        assertNull(server.installs[installId]!!.account)
    }

    @Test fun invalidAssertionAndPreferencesRules() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        try { c.identify("forged"); fail() } catch (e: AppReferEngagementException) { assertEquals(AppReferEngagementException.Reason.INVALID_ASSERTION, e.reason) }
        assertEquals(0, c.stateForTesting().first)
        c.identify("ok:user-a")
        assertTrue(c.setPreferences(mapOf("marketing" to true)).marketing)
        c.logout()
        assertEquals(false, server.installs[installId]!!.prefs["marketing"])
        server.offline = true
        c.logout()
        server.offline = false
        try { c.setPreferences(mapOf("marketing" to true)); fail() } catch (e: AppReferEngagementException) { assertEquals(AppReferEngagementException.Reason.IDENTITY_CHANGED, e.reason) }
    }

    @Test fun receiptsOnlyForTheCurrentIdentityAndDroppedOnChange() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        val token = "er1." + "A".repeat(43)
        c.queueReceipt("opened", "sparkly_live_j_a_nudge", token, installId, 0, null, System.currentTimeMillis())
        val sent = server.calls.last { it.path.endsWith("/receipts") }
        @Suppress("UNCHECKED_CAST") val item = (sent.body!!["receipts"] as List<Map<String, Any?>>).single()
        assertEquals(token, item["token"]); assertEquals("opened", item["kind"])
        assertEquals(0, c.pendingReceiptCount)
        c.queueReceipt("opened", "sparkly_live_j_b_nudge", token, installId, 7, null, System.currentTimeMillis())
        assertEquals("another epoch is never queued", 1, server.count("POST", "/api/engagement/v1/installs/me/receipts"))
        server.offline = true
        c.queueReceipt("opened", "sparkly_live_j_c_nudge", token, installId, 0, null, System.currentTimeMillis())
        assertEquals(1, c.pendingReceiptCount)
        server.offline = false
        c.identify("ok:user-b")
        assertEquals("never sent under the next account", 0, c.pendingReceiptCount)
        server.receiptStatus = "rejected"
        c.queueReceipt("dismissed", "sparkly_live_j_d_nudge", token, installId, c.snapshot.get()!!.accountEpoch!!, null, System.currentTimeMillis())
        assertEquals(0, c.pendingReceiptCount)
    }

    @Test fun deleteInstallOfflineIsRetriedBeforeANewRegistration() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        server.offline = true
        c.deleteInstall()
        assertEquals(AppReferEngagementStatus.Pending, c.status)
        assertNull(c.snapshot.get())
        server.offline = false
        val fresh = id(client().start(key, true, AppReferEngagementOptions()))!!
        assertNull(server.installs[installId])
        assertNotEquals(installId, fresh)
    }

    // --- Review fixes: cold restarts and explicit epochs

    @Test fun aColdRestartAfterABackendChangeNeverReusesTheOldStamp() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        c.identify("ok:user-a")
        val underA = c.snapshot.get()!!
        server.backendBind(installId, "user-b") // while the app isn't running
        val restarted = client().also { it.start(key, true, AppReferEngagementOptions()) }
        val underB = restarted.snapshot.get()!!
        assertEquals(2, underB.accountEpoch)
        assertTrue("the persisted epoch shows the change", underB.serial > underA.serial)
        assertNotEquals(underA.stamp, underB.stamp)
        server.backendBind(installId, null) // to anonymous
        val again = client().also { it.start(key, true, AppReferEngagementOptions()) }
        assertTrue(again.snapshot.get()!!.serial > underB.serial)
        assertNotEquals(underB.stamp, again.snapshot.get()!!.stamp)
        val same = client().also { it.start(key, true, AppReferEngagementOptions()) }
        assertEquals("nothing changed: same stamp", again.snapshot.get()!!.stamp, same.snapshot.get()!!.stamp)
        server.installs.remove(installId) // erased: replaced
        val replaced = client().also { it.start(key, true, AppReferEngagementOptions()) }
        assertNotEquals(installId, replaced.snapshot.get()!!.installId)
        assertNotEquals(same.snapshot.get()!!.stamp, replaced.snapshot.get()!!.stamp)
    }

    @Test fun preferencesStateTheirEpochAndAreRefusedForTheNextAccount() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        c.identify("ok:user-a")
        c.preferences()
        server.backendBind(installId, "user-b")
        try { c.setPreferences(mapOf("marketing" to true)); fail() } catch (e: AppReferEngagementException) { assertEquals(AppReferEngagementException.Reason.IDENTITY_CHANGED, e.reason) }
        assertEquals(1, server.calls.last { it.method == "PUT" }.body!!["expectedEpoch"])
        assertEquals("A's cached choice never reaches B", false, server.installs[installId]!!.prefs["marketing"])
        c.setPreferences(mapOf("marketing" to true))
        assertEquals(2, server.calls.last { it.method == "PUT" }.body!!["expectedEpoch"])
        assertEquals(true, server.installs[installId]!!.prefs["marketing"])
    }

    @Test fun readingPreferencesLearnsTheirEpoch() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        val before = c.snapshot.get()!!.serial
        server.backendBind(installId, "user-b")
        c.preferences()
        assertEquals(1, c.snapshot.get()!!.accountEpoch)
        assertTrue(c.snapshot.get()!!.serial > before)
    }

    @Test fun aStaleLogoutNeverDetachesTheNextAccount() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        c.identify("ok:user-a")
        server.offline = true
        c.logout() // saved for epoch 1
        server.offline = false
        server.backendBind(installId, "user-b")
        client().start(key, true, AppReferEngagementOptions())
        assertEquals(1, server.calls.last { it.path.endsWith("/logout") }.body!!["expectedEpoch"])
        assertEquals("refused (409): B stays bound", "user-b", server.installs[installId]!!.account)
        assertFalse("settled, not retried forever", client().stateForTesting().third)
    }

    @Test fun aSignInStartedUnderAnOldIdentityDoesNotReplaceTheNewOne() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        server.backendBind(installId, "user-b")
        try { c.identify("ok:user-a"); fail() } catch (e: AppReferEngagementException) { assertEquals(AppReferEngagementException.Reason.IDENTITY_CHANGED, e.reason) }
        assertEquals(0, server.calls.last { it.path.endsWith("/account") }.body!!["expectedEpoch"])
        assertEquals("user-b", server.installs[installId]!!.account)
        assertEquals(1, c.snapshot.get()!!.accountEpoch)
    }

    @Test fun queuedEventsCarryTheEpochTheyWereQueuedUnder() = runBlocking {
        val c = client()
        c.start(key, true, AppReferEngagementOptions())
        AppReferEngagement.client = c
        try {
            val stamp = AppReferEngagement.eventStamp("setup_started", key)!!
            assertEquals(mapOf("X-AppRefer-Install" to c.snapshot.get()!!.header, "X-AppRefer-Install-Epoch" to "0"), AppReferEngagement.headersFor(stamp))
            c.identify("ok:user-a")
            assertNull("another identity: the queued event goes without it", AppReferEngagement.headersFor(stamp))
            val snap = c.snapshot.get()!!
            assertNotEquals("same serial, another epoch is another stamp", snap.stamp, snap.copy(accountEpoch = 9).stamp)
        } finally { AppReferEngagement.client = null }
    }

    @Test fun deferredEventsRecheckTheRealClockAndIdentity() {
        val now = System.currentTimeMillis()
        val snap = EngagementSnapshot("s", "ei_" + "a".repeat(26), 2, 1, "h", null)
        fun raw(exp: Long, epoch: Int = 2) = org.json.JSONObject().put("expiresAt", exp).put("installId", snap.installId).put("accountEpoch", epoch)
        val open = AppReferEngagementNotificationEvent(AppReferEngagementNotificationEvent.Kind.OPENED, "m", "reminders", "/x", null, null)
        val dismissed = open.copy(kind = AppReferEngagementNotificationEvent.Kind.DISMISSED, destination = null)
        assertTrue(AppReferEngagement.stillDeliverable(open, raw(now + 1000), snap, now))
        assertFalse("tapped while valid, expired while waiting", AppReferEngagement.stillDeliverable(open, raw(now + 1000), snap, now + 1000))
        assertFalse(AppReferEngagement.stillDeliverable(dismissed, raw(now + 1000), snap, now + 2000))
        assertFalse("no expiry recorded: never trusted", AppReferEngagement.stillDeliverable(open, org.json.JSONObject().put("installId", snap.installId).put("accountEpoch", 2), snap, now))
        assertFalse(AppReferEngagement.stillDeliverable(open, raw(now + 1000, epoch = 1), snap, now))
        assertTrue(AppReferEngagement.stillDeliverable(dismissed, raw(now + 1000, epoch = 1), snap, now))
        assertFalse(AppReferEngagement.stillDeliverable(open, raw(now + 1000), null, now))
    }

    @Test fun aBackendSideAccountChangeMovesTheSerial() = runBlocking {
        val c = client()
        val installId = id(c.start(key, true, AppReferEngagementOptions()))!!
        val before = c.snapshot.get()!!.serial
        server.installs[installId]!!.epoch = 5
        c.setPushToken("fcm:" + "y".repeat(40))
        assertEquals(5, c.snapshot.get()!!.accountEpoch)
        assertTrue(c.snapshot.get()!!.serial > before)
    }
}
