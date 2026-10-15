package com.apprefer.sdk.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Independent of first-open deduplication: only successful reports advance the saved value. */
internal class ConsentReporter {
    private val mutex = Mutex()
    suspend fun report(
        current: () -> String?,
        reported: () -> String?,
        send: suspend (String) -> Map<String, Any?>?,
        refresh: (Map<String, Any?>) -> Unit,
        save: (String) -> Unit,
    ) = mutex.withLock {
        val consent = current() ?: return@withLock
        if (consent == reported()) return@withLock
        val response = send(consent) ?: return@withLock
        refresh(response)
        save(consent)
    }
}
