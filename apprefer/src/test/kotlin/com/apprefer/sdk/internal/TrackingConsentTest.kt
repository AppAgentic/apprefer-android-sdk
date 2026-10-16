package com.apprefer.sdk.internal

import com.apprefer.sdk.TrackingConsent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class TrackingConsentTest {
    @Test fun mappingAndHostPolicy() {
        assertNull(TrackingConsent.mapped(null))
        assertEquals("granted", TrackingConsent.mapped(true))
        assertEquals("denied", TrackingConsent.mapped(false))
        assertEquals(listOf("https://trk.apprefer.com"), TrackingConsent.hosts("granted"))
        for (c in listOf("denied", "undetermined")) {
            assertEquals(listOf("https://apprefer.com"), TrackingConsent.hosts(c))
            assertFalse(TrackingConsent.allowed(c))
        }
        assertEquals(2, TrackingConsent.hosts(null).size)
        assertTrue(TrackingConsent.allowed(null))
    }

    @Test fun currentConsentIsReadForEveryRequestAndKnownConsentNeverFallsBack() = runBlocking {
        var consent: String? = null
        val seen = mutableListOf<String>()
        val client = HttpClient("pk", Logger(false, 0), consentProvider = { consent },
            transport = { host, _, _ -> seen.add(host); null })
        client.post("/api/track/event", emptyMap())
        assertEquals(listOf("https://trk.apprefer.com", "https://apprefer.com"), seen)
        for (c in listOf("denied", "undetermined", "granted")) {
            seen.clear()
            consent = c
            client.post("/api/track/event", emptyMap())
            assertEquals(TrackingConsent.hosts(c), seen)
        }
    }

    @Test fun advancedMatchingIsDroppedLocally() = runBlocking {
        var consent: String? = "denied"
        val seen = mutableListOf<String>()
        val client = HttpClient("pk", Logger(false, 0), consentProvider = { consent },
            transport = { host, _, _ -> seen.add(host); emptyMap() })
        for (c in listOf("denied", "undetermined")) {
            consent = c
            client.post("/api/track/event", mapOf("event_name" to "_advanced_matching"))
        }
        assertTrue(seen.isEmpty())
        consent = "granted"
        client.post("/api/track/event", mapOf("event_name" to "_advanced_matching"))
        assertEquals(listOf("https://trk.apprefer.com"), seen)
    }

    @Test fun changesRetryUntilSuccessfulAndRefreshBeforeSaving() = runBlocking {
        val reporter = ConsentReporter()
        var current: String? = "denied"
        var saved: String? = null
        var succeeds = false
        var sends = 0
        var refreshed = 0
        suspend fun report() = reporter.report({ current }, { saved },
            { sends++; if (succeeds) emptyMap() else null },
            { refreshed++ }, { saved = it })
        report()
        assertNull(saved)
        succeeds = true
        report()
        assertEquals("denied", saved)
        assertEquals(1, refreshed)
        report()
        assertEquals(2, sends)
        current = "granted"
        report()
        assertEquals("granted", saved)
        assertEquals(2, refreshed)
        current = null
        report()
        assertEquals(3, sends)
    }

    @Test fun firstOpenMustFinishBeforeActivationCanReport() = runBlocking {
        val reporter = ConsentReporter()
        var attempted = false
        var current = "denied"
        var reported: String? = null
        val sent = mutableListOf<String>()
        suspend fun report() = reporter.report({ current }, { reported },
            { sent.add(requireNotNull(it)); emptyMap() }, {}, { reported = it },
            eligible = { attempted })
        report()
        current = "granted" // ATT answered while configure was in flight.
        report()
        assertTrue(sent.isEmpty())
        reported = "denied" // First open accepted its original snapshot.
        attempted = true
        report() // configure's final check.
        assertEquals(listOf("granted"), sent)
        assertEquals("granted", reported)
    }

    @Test fun killSwitchPreservesCacheAndReportedConsentAndBlocksLaterCalls() = runBlocking {
        val reporter = ConsentReporter()
        var enabled = true
        var saved: String? = "denied"
        var cache = "paid attribution"
        var calls = 0
        suspend fun report() = reporter.report({ "granted" }, { saved },
            { calls++; mapOf("sdkEnabled" to false) },
            { cache = "cleared" }, { saved = it },
            eligible = { enabled }, disabled = { enabled = false })
        report()
        assertFalse(enabled)
        assertEquals("denied", saved)
        assertEquals("paid attribution", cache)
        report()
        assertEquals(1, calls)
    }

    @Test fun checkDuringInflightReportReadsNewConsentAfterCompletion() = runBlocking {
        val reporter = ConsentReporter()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var current = "denied"
        var saved: String? = null
        val sent = mutableListOf<String>()
        suspend fun report() = reporter.report({ current }, { saved },
            {
                sent.add(requireNotNull(it))
                if (sent.size == 1) { started.complete(Unit); finish.await() }
                emptyMap()
            }, {}, { saved = it })
        val first = launch { report() }
        started.await()
        current = "granted"
        val second = launch { report() }
        yield()
        assertEquals(listOf("denied"), sent)
        finish.complete(Unit)
        first.join()
        second.join()
        assertEquals(listOf("denied", "granted"), sent)
        assertEquals("granted", saved)
    }
}
