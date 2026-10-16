package com.apprefer.sdk.internal

import com.apprefer.sdk.models.Attribution
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Identical callers share first-open work, including its attribution result. */
internal class ConfigureCoordinator(private val scope: CoroutineScope) {
    private val mutex = Mutex()
    private var running: Pair<List<Any?>, Deferred<Attribution?>>? = null
    suspend fun run(config: List<Any?>, work: suspend () -> Attribution?): Attribution? {
        val task = mutex.withLock {
            running?.takeIf { it.first == config && it.second.isActive }?.second
                ?: scope.async { work() }.also { running = config to it }
        }
        return try { task.await() } finally {
            mutex.withLock { if (running?.second === task && task.isCompleted) running = null }
        }
    }
}
