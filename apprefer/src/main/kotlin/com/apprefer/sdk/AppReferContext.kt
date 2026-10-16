package com.apprefer.sdk

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/** Obtains storage access at process startup without configuring attribution or Support. */
internal object AppReferContext { @Volatile var context: Context? = null }
class AppReferInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext
        AppReferContext.context = app
        com.apprefer.sdk.support.AppReferSupport.resumeRevocations()
        (app as? android.app.Application)?.registerActivityLifecycleCallbacks(object : android.app.Application.ActivityLifecycleCallbacks {
            private var started = 0
            private var changingConfiguration = false
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityCreated(activity: android.app.Activity, state: android.os.Bundle?) {}
            override fun onActivityStarted(activity: android.app.Activity) {
                if (started++ == 0 && !changingConfiguration) com.apprefer.sdk.support.AppReferSupport.resumeRevocations()
            }
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivityStopped(activity: android.app.Activity) {
                changingConfiguration = activity.isChangingConfigurations
                started--
            }
            override fun onActivitySaveInstanceState(activity: android.app.Activity, state: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        return true
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
