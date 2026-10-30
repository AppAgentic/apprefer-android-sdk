package com.apprefer.sdk.billing

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

@OptIn(ExperimentalCoroutinesApi::class)
class BillingLegacyAccountTokenTest {
    @Test fun legacyHashUnlocksOnlineAndImportsAfterOldCompletionWithoutRestore() = runTest {
        val user = "Legacy-User/CaseSensitive"
        val legacy = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(user.toByteArray(Charsets.UTF_8)))
        assertEquals(legacy, legacyPlayAccountToken(user))
        val h = Harness(this, user)
        h.storage.setAccountToken("11111111-2222-3333-4444-555555555555", user)
        val oldKey = MessageDigest.getInstance("SHA-256").digest(user.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        File(h.storage.directory, "migration-$oldKey.flag").writeText("done")
        assertFalse(h.storage.migrationSynced(user))
        h.play.purchases += storePurchase("legacy", "pro_monthly", acknowledged = true, accountId = legacy)
        val online = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
        assertEquals(user, online.appUserId)
        assertTrue("Missing premium: ${online.entitlements}; tag=$legacy", online.entitlements["premium"]?.isActive == true)
        h.engine.migrationSync()
        h.engine.migrationSync()
        val requests = h.server.requests.filter { it.url.contains("/purchases") }
        assertEquals(1, requests.size)
        val body = JSONObject(requests.single().body!!)
        assertEquals(user, body.getString("appUserId"))
        assertFalse(body.has("restore"))
        assertTrue(h.storage.migrationSynced(user))
        assertTrue(h.play.acknowledged.isEmpty())
        assertTrue(h.play.consumed.isEmpty())
    }

    @Test fun anotherUserOrChangedCaseHashNeverUnlocksOrImports() = runTest {
        for (tagOwner in listOf("ALICE", "bob")) {
            val h = Harness(this, "alice")
            val tag = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(tagOwner.toByteArray(Charsets.UTF_8)))
            h.play.purchases += storePurchase("foreign", "pro_monthly", acknowledged = true, accountId = tag)
            val online = h.engine.customerInfo(AppReferBilling.FetchPolicy.FETCH_CURRENT)
            assertFalse(online.entitlements["premium"]?.isActive == true)
            h.engine.migrationSync()
            assertTrue(h.server.requests.none { it.url.contains("/purchases") })
        }
    }
}
