package com.apprefer.sdk.billing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingMigrationReviewTest {
    @Test fun openPurchasesSkipImportAndOldMarkerNeverSuppressesNormalPath() = runTest {
        val h = Harness(this, "alice")
        h.play.purchases += storePurchase("open", "coins_100", isSubscription = false)
        h.storage.markMigrationPurchase("open")
        h.engine.migrationSync()
        assertTrue(h.server.requests.none { it.url.contains("/purchases") })
        h.engine.syncPurchases()
        assertEquals(listOf("open"), h.play.consumed)
    }

    @Test fun onlyUntaggedAndCurrentUserTagsAreImported() = runTest {
        val h = Harness(this, "alice")
        h.storage.setAccountToken("11111111-2222-4333-8444-555555555555", "alice")
        h.storage.setAccountToken("22222222-3333-4444-8555-666666666666", "bob")
        h.play.purchases += storePurchase("untagged", "pro_monthly", acknowledged = true)
        h.play.purchases += storePurchase("11111111-2222-4333-8444-555555555555", "pro_monthly", acknowledged = true, accountId = "11111111-2222-4333-8444-555555555555")
        h.play.purchases += storePurchase("22222222-3333-4444-8555-666666666666", "pro_monthly", acknowledged = true, accountId = "22222222-3333-4444-8555-666666666666")
        h.play.purchases += storePurchase("unknown", "pro_monthly", acknowledged = true, accountId = "unknown")
        h.engine.migrationSync()
        assertEquals(2, h.server.requests.count { it.url.contains("/purchases") })
    }

    @Test fun partialProgressSurvivesRestartAndOnlyLeftoversRetry() = runTest {
        val h = Harness(this, "alice")
        h.play.purchases += storePurchase("good", "pro_monthly", acknowledged = true)
        h.play.purchases += storePurchase("leftover", "pro_monthly")
        h.engine.migrationSync()
        assertFalse(h.storage.migrationSynced("alice"))
        val second = Harness(this, "alice", storage = newStorage(dir = h.storage.directory), play = h.play)
        second.engine.migrationSync()
        assertTrue(second.server.requests.none { it.url.contains("/purchases") })
        h.play.purchases.replaceAll { it.copy(isAcknowledged = true) }
        second.engine.migrationSync()
        assertEquals(1, second.server.requests.count { it.url.contains("/purchases") })
        assertTrue(second.storage.migrationSynced("alice"))
    }

    @Test fun staleExplicitUserAndSwitchDuringQueryAbortThroughStorageGate() = runTest {
        val h = Harness(this, "alice")
        h.play.purchases += storePurchase("legacy", "pro_monthly", acknowledged = true)
        h.engine.migrationSync("bob")
        assertEquals(0, h.play.queryCount)
        h.play.onQuery = { h.storage.loggedInAppUserId = "bob" }
        h.engine.migrationSync("alice")
        assertTrue(h.server.requests.none { it.url.contains("/purchases") })
        assertFalse(h.storage.migrationSynced("alice"))

        var locked = false
        val inner = InMemoryKeyValueStore()
        val gated = object : BillingKeyValueStore {
            override fun getString(key: String): String? {
                if (locked) throw BillingStorageException("locked")
                return inner.getString(key)
            }
            override fun putString(key: String, value: String?, durable: Boolean) = inner.putString(key, value, durable)
        }
        val unavailable = Harness(this, "alice", storage = newStorage(store = gated))
        locked = true
        unavailable.engine.migrationSync("alice")
        assertEquals(0, unavailable.play.queryCount)
        assertTrue(unavailable.server.requests.isEmpty())
    }

    @Test fun rejectedImportIsNotNormalDurabilityAndRestoreSendsOnce() = runTest {
        val h = Harness(this, "alice")
        h.play.purchases += storePurchase("legacy", "pro_monthly", acknowledged = true)
        h.server.purchasesStatus = 422
        h.engine.migrationSync()
        assertEquals("legacy", h.rejected.entries.single().purchaseToken)
        assertTrue(h.rejected.entries.single().migrationOnly)
        assertFalse(h.rejected.isDurable("legacy"))
        h.server.purchasesStatus = 200
        val before = h.server.requests.count { it.url.contains("/purchases") }
        h.engine.restore()
        assertEquals(1, h.server.requests.count { it.url.contains("/purchases") } - before)
    }

    @Test fun startHandlesOpenAndFetchesBeforeBackgroundImport() = runTest {
        val h = Harness(this, "alice")
        h.play.purchases += storePurchase("open", "pro_monthly")
        h.play.purchases += storePurchase("closed", "pro_monthly", acknowledged = true)
        h.engine.start()
        assertTrue(h.server.requests.any { it.url.contains("/purchases") })
        assertTrue(h.server.requests.any { it.url.contains("/customers") })
        advanceUntilIdle()
        assertTrue(h.play.queryCount >= 2)
        assertEquals(2, h.server.requests.count { it.url.contains("/purchases") })
        val customer = h.server.requests.indexOfFirst { it.url.contains("/customers") }
        val imported = h.server.requests.indexOfFirst { it.body?.contains("closed") == true && it.url.contains("/purchases") }
        assertTrue(imported > customer)
    }
}
