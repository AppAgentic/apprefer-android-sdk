package com.apprefer.sdk.internal

import android.content.Context

/**
 * Written on `AppRefer.reset()` into AppRefer Billing's own storage (`apprefer_billing`
 * SharedPreferences): the Billing app user id that was current at that moment, anonymous or
 * not. While it still names the current Billing user, Billing sends that user no install
 * device id, never re-links it and identify sends `link: false`, across relaunches too.
 * Billing clears it on logIn/logOut or when its user id changes. Nothing is written when
 * Billing has never stored a user on this install.
 *
 * Two exceptions, both about Billing's logIn/logOut in this process ([identitySet]):
 * - the fresh anonymous id a logOut() minted is never marked (apps often sign out of Billing
 *   before, or without awaiting, reset()), until a logIn or another logOut replaces it;
 * - a reset() whose write would land after a logIn/logOut that ran since the reset started is
 *   dropped, so a late write never outlives them.
 */
internal object BillingResetMarker {
    const val PREFS_NAME = "apprefer_billing"
    const val KEY_RESET_USER = "apprefer_billing_reset_user"
    private const val KEY_APP_USER_ID = "apprefer_billing_app_user_id"
    private const val KEY_ANONYMOUS_ID = "apprefer_billing_anonymous_id"

    private val lock = Any()
    /** The anonymous id the last Billing logOut() in this process minted; null after a logIn. */
    private var loggedOutAnonymousId: String? = null
    /** Advances on every Billing logIn/logOut in this process. */
    private var identityGeneration = 0L

    /** Taken when reset() starts; [mark] applies only if no logIn/logOut ran since. */
    fun generation(): Long = synchronized(lock) { identityGeneration }

    /**
     * Billing logIn/logOut set the user: [freshAnonymousId] is the id a logOut minted (null for a
     * logIn). [clearMarker] runs under the same lock as [mark], after the new user is stored.
     */
    fun identitySet(freshAnonymousId: String?, clearMarker: () -> Unit) = synchronized(lock) {
        identityGeneration++
        loggedOutAnonymousId = freshAnonymousId
        clearMarker()
    }

    /** Returns whether a marker was saved. [since] is [generation] at the start of reset(). */
    fun mark(context: Context, since: Long): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mark(since, { prefs.getString(it, null) }, { k, v -> prefs.edit().putString(k, v).commit() })
    } catch (_: Exception) {
        false
    }

    internal fun mark(since: Long, read: (String) -> String?, write: (String, String) -> Boolean): Boolean = synchronized(lock) {
        // A logIn/logOut ran after this reset() started: it already said who the user is now.
        if (identityGeneration != since) return@synchronized false
        val user = read(KEY_APP_USER_ID)?.takeIf { it.isNotEmpty() }
            ?: read(KEY_ANONYMOUS_ID)?.takeIf { it.isNotEmpty() }
            ?: return@synchronized false
        // Signed out of Billing first: the new anonymous user is not the previous account.
        if (user == loggedOutAnonymousId) return@synchronized false
        write(KEY_RESET_USER, user)
    }

    /** Tests: forget this process's logIn/logOut history. */
    internal fun resetForTests() = synchronized(lock) { loggedOutAnonymousId = null; identityGeneration = 0 }
}
