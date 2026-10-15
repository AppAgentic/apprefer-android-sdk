package com.apprefer.sdk.internal

import com.apprefer.sdk.TrackingConsent
import kotlinx.coroutines.runBlocking
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
}
