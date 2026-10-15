package com.apprefer.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class DeviceIdManagerTest {

    private class FakeStore(var value: String? = null) : DeviceIdStore {
        var readFailures = 0
        var writes = 0
        var writeWorks = true

        override fun readDeviceId(): String? {
            if (readFailures > 0) {
                readFailures--
                throw ClassCastException("not a string")
            }
            return value
        }

        override fun writeDeviceId(value: String): Boolean {
            writes++
            Thread.sleep(5) // widen the first-launch race
            if (writeWorks) this.value = value
            return writeWorks
        }
    }

    private val logger = Logger(debug = false, logLevel = 0)

    @Test
    fun returnsSavedId() {
        val store = FakeStore("saved")
        assertEquals("saved", DeviceIdManager(store, { "abcdef0123456789" }, logger).getOrCreate())
        assertEquals(0, store.writes)
    }

    @Test
    fun mintsAndSavesOnlyWhenMissing() {
        val store = FakeStore()
        assertEquals("abcdef0123456789", DeviceIdManager(store, { "abcdef0123456789" }, logger).getOrCreate())
        assertEquals("abcdef0123456789", store.value)
    }

    @Test
    fun unreadableIdIsNeverReplaced() {
        val store = FakeStore("saved").apply { readFailures = 2 }
        assertNull(DeviceIdManager(store, { "abcdef0123456789" }, logger).getOrCreate())
        assertEquals(0, store.writes)
        assertEquals("saved", store.value)
    }

    @Test
    fun unsavedRandomIdIsNotUsed() {
        val store = FakeStore().apply { writeWorks = false }
        assertNull(DeviceIdManager(store, { null }, logger).getOrCreate())
        // Saved on a later launch, then used.
        store.writeWorks = true
        val id = DeviceIdManager(store, { null }, logger).getOrCreate()
        assertEquals(store.value, id)
    }

    @Test
    fun unsavedAndroidIdIsStillUsedBecauseItIsStable() {
        val store = FakeStore().apply { writeWorks = false }
        assertEquals("abcdef0123456789", DeviceIdManager(store, { "abcdef0123456789" }, logger).getOrCreate())
    }

    @Test
    fun transientReadFailureIsRetriedOnce() {
        val store = FakeStore("saved").apply { readFailures = 1 }
        assertEquals("saved", DeviceIdManager(store, { null }, logger).getOrCreate())
    }

    @Test
    fun concurrentFirstLaunchCallersGetTheSameId() {
        val store = FakeStore()
        // ANDROID_ID unusable: each mint would be a random UUID.
        val managers = List(8) { DeviceIdManager(store, { null }, logger) }
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val ids = java.util.Collections.synchronizedList(mutableListOf<String?>())
        managers.forEach { m -> pool.execute { start.await(); ids += m.getOrCreate() } }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS))
        assertEquals(1, ids.toSet().size)
        assertEquals(1, store.writes)
    }
}
