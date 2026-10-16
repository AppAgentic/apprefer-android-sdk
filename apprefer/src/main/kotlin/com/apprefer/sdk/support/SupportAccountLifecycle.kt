package com.apprefer.sdk.support

/** A detached embedded chat must still observe the next account on reattachment. */
internal class SupportAccountLifecycle(
    private val generation: () -> Int,
    private val listeners: MutableSet<() -> Unit>,
    private val rebuild: () -> Unit,
) {
    private var seen = generation()
    private val listener: () -> Unit = { seen = generation(); rebuild() }
    fun attach() {
        listeners.add(listener)
        if (seen != generation()) listener()
    }
    fun detach() { listeners.remove(listener) }
}
