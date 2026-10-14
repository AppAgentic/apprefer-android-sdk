package com.apprefer.sdk.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class BillingMigrationTest {
    @Test fun acknowledgedPurchasesImportOnceAndNewLoginImportsAgainWithoutRestore() = runTest {
        val h = Harness(this, "alice")
        h.play.purchases += storePurchase("sub", "pro_monthly", acknowledged = true)
        h.play.purchases += storePurchase("coin", "lifetime", isSubscription = false, acknowledged = true)
        h.engine.migrationSync()
        assertTrue(h.storage.migrationSynced("alice"))
        h.engine.migrationSync()
        assertEquals(2, h.server.requests.count { it.url.contains("/purchases") })
        h.engine.logIn("bob")
        advanceUntilIdle()
        assertTrue(h.storage.migrationSynced("bob"))
        val bodies = h.server.requests.filter { it.url.contains("/purchases") }.map { JSONObject(it.body!!) }
        assertEquals(setOf("alice", "bob"), bodies.map { it.getString("appUserId") }.toSet())
        assertTrue(bodies.all { !it.has("restore") })
        assertTrue(h.play.acknowledged.isEmpty())
        assertTrue(h.play.consumed.isEmpty())
    }

    @Test fun failedEnumerationAndFailedPersistenceNeverSetFlag() = runTest {
        val h = Harness(this, "alice")
        h.play.queryCode = BillingResponseCode.SERVICE_UNAVAILABLE
        h.engine.migrationSync()
        assertFalse(h.storage.migrationSynced("alice"))
        h.play.queryCode = BillingResponseCode.OK
        h.play.purchases += storePurchase("legacy", "pro_monthly", acknowledged = true)
        File(h.storage.directory, "pending_purchases.json").apply { mkdirs(); File(this, "block").writeText("cannot replace") }
        h.server.purchasesUnreachable = true
        h.engine.migrationSync()
        assertFalse(h.storage.migrationSynced("alice"))
        assertTrue(h.play.acknowledged.isEmpty())
        assertTrue(h.play.consumed.isEmpty())
    }

    @Test fun durableQueueReplayNeverConsumesLegacyConsumable() = runTest {
        val h = Harness(this, "alice")
        h.play.purchases += storePurchase("legacy", "lifetime", isSubscription = false, acknowledged = true)
        h.server.purchasesUnreachable = true
        h.engine.migrationSync()
        assertTrue(h.storage.migrationSynced("alice"))
        assertTrue(h.queue.entries.single().migrationOnly)
        assertTrue(h.queue.isDurable(h.queue.entries.single().queueKey))
        val reloaded = PendingPurchaseQueue(File(h.storage.directory, "pending_purchases.json"))
        assertTrue(reloaded.entries.single().migrationOnly)
        h.server.purchasesUnreachable = false
        h.engine.drainQueue()
        assertTrue(h.queue.isEmpty)
        assertTrue(h.play.acknowledged.isEmpty())
        assertTrue(h.play.consumed.isEmpty())
    }
    @Test fun startupHandlesOpenPurchasesNormallyBeforeMigration() = runTest {
        val h = Harness(this, "alice")
        h.play.purchases += storePurchase("legacy", "lifetime", isSubscription = false)
        h.engine.start()
        h.engine.syncPurchases()
        val reloadedStorage = newStorage(dir = h.storage.directory)
        val second = Harness(this, "alice", storage = reloadedStorage, play = h.play)
        second.engine.start()
        assertTrue(h.play.purchases.single().isAcknowledged)
        assertTrue(h.play.consumed.isEmpty())
        assertTrue(second.server.requests.none { it.url.contains("/purchases") })
    }

    @Test fun backoffNeverCountsAnAttemptOrCompletesAnImport() = runTest {
        for (status in listOf(401, 403, 404, 409, 429)) {
            val h = Harness(this, "alice")
            h.play.purchases += storePurchase("one", "pro_monthly", acknowledged = true)
            h.play.purchases += storePurchase("two", "lifetime", isSubscription = false, acknowledged = true)
            h.server.purchasesStatus = status
            h.engine.migrationSync()
            assertTrue(h.storage.migrationSynced("alice"))
            assertEquals(2, h.queue.entries.size)
            assertTrue(h.queue.entries.all { it.attempts == 0 && !it.restore && it.migrationOnly && h.queue.isDurable(it.queueKey) })
            val sent = h.server.requests.size
            h.engine.drainQueue()
            assertEquals(sent, h.server.requests.size)
            assertTrue(h.play.acknowledged.isEmpty())
            assertTrue(h.play.consumed.isEmpty())
        }
    }

}
