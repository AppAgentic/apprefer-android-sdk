package com.apprefer.sdk.internal

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serial checks read the latest choice after any running report finishes. */
internal class ConsentReporter {
    private val mutex = Mutex()
    suspend fun report(
        current: () -> String?,
        reported: () -> String?,
        send: suspend (String) -> Map<String, Any?>?,
        refresh: (Map<String, Any?>) -> Unit,
        save: (String) -> Unit,
        eligible: () -> Boolean = { true },
        disabled: () -> Unit = {},
    ) = mutex.withLock {
        if (!eligible()) return@withLock
        val consent = current() ?: return@withLock
        if (consent == reported()) return@withLock
        val response = send(consent) ?: return@withLock
        if (response["sdkEnabled"] == false) {
            disabled()
            return@withLock
        }
        refresh(response)
        save(consent)
    }
}
