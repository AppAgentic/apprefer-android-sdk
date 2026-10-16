package com.apprefer.sdk.internal

import android.content.Context

/**
 * Written on `AppRefer.reset()` into AppRefer Billing's own storage (`apprefer_billing`
 * SharedPreferences): the Billing app user id that was current at that moment, anonymous or
 * not. While it still names the current Billing user, Billing sends that user no install
 * device id, never re-links it and identify sends `link: false`, across relaunches too.
 * Billing clears it on logIn/logOut or when its user id changes. Nothing is written when
 * Billing has never stored a user on this install.
 */
internal object BillingResetMarker {
    const val PREFS_NAME = "apprefer_billing"
    const val KEY_RESET_USER = "apprefer_billing_reset_user"
    private const val KEY_APP_USER_ID = "apprefer_billing_app_user_id"
    private const val KEY_ANONYMOUS_ID = "apprefer_billing_anonymous_id"

    /** Returns whether a marker was saved. */
    fun mark(context: Context): Boolean = try {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val user = prefs.getString(KEY_APP_USER_ID, null)?.takeIf { it.isNotEmpty() }
            ?: prefs.getString(KEY_ANONYMOUS_ID, null)?.takeIf { it.isNotEmpty() }
        user != null && prefs.edit().putString(KEY_RESET_USER, user).commit()
    } catch (_: Exception) {
        false
    }
}
