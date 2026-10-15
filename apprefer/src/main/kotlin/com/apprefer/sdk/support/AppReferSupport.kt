package com.apprefer.sdk.support

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Build
import com.apprefer.sdk.AppRefer
import com.apprefer.sdk.internal.AppReferStorage
import kotlinx.coroutines.*
import java.util.Locale

/** In-app support. Configure AppRefer before opening. Network methods are suspending. */
object AppReferSupport {
    var accentColor: Int = Color.rgb(120, 82, 255)
    var title: String? = null
    var replyTimeText: String = "Ask us anything. We usually reply in under an hour."
    @Volatile internal var name: String? = null
    @Volatile private var email: String? = null
    @Volatile private var app: Context? = null
    private var client: SupportAPI? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized internal fun configure(context: Context) {
        if (app != null) return
        app = context.applicationContext
        if (Build.VERSION.SDK_INT >= 26) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("apprefer_support", "Support replies", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    internal fun api(context: Context): SupportAPI {
        configure(context)
        return synchronized(this) {
            client ?: SupportAPI({ AppRefer.currentApiKey() }, SecureSupportStore(context), {
                val storage = AppReferStorage(context)
                linkedMapOf<String, Any?>("appUserId" to storage.getUserId(), "deviceId" to AppRefer.getDeviceId(), "name" to name, "email" to email).filterValues { it != null }
            }, { deviceContext(context) }).also { client = it }
        }
    }
    fun identify(name: String? = null, email: String? = null) {
        this.name = name?.trim()?.takeIf { it.isNotEmpty() }; this.email = email?.trim()?.takeIf { it.isNotEmpty() }
        identityChanged()
    }
    internal fun identityChanged() { app?.let { c -> scope.launch { try { api(c).syncIdentity() } catch (_: Exception) {} } } }
    @JvmOverloads fun open(conversationId: String? = null) { app?.let { open(it, conversationId) } }
    @JvmOverloads fun open(context: Context, conversationId: String? = null) { launch(context, conversationId, null) }
    @JvmOverloads fun compose(prefill: String = "") { app?.let { launch(it, null, prefill) } }
    @JvmOverloads fun compose(context: Context, prefill: String = "") { launch(context, null, prefill) }
    private fun launch(context: Context, id: String?, draft: String?) {
        val intent = Intent(context, AppReferSupportActivity::class.java).putExtra("conversationId", id).putExtra("draft", draft)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
    suspend fun registerPushToken(token: String) { val c = app ?: error("Configure AppRefer first"); api(c).register(token) }
    suspend fun removePushToken(token: String) { val c = app ?: error("Configure AppRefer first"); api(c).remove(token) }
    fun handleNotification(data: Map<String, String>): Boolean {
        if (data["type"] != "support_reply" || app == null) return false
        open(data["conversationId"]); return true
    }
    suspend fun unreadCount(): Int = app?.let { api(it).conversations().sumOf { c -> c.unread } } ?: 0
    internal fun resolvedTitle(context: Context): String = title ?: "${context.applicationInfo.loadLabel(context.packageManager)} support"
    @Suppress("DEPRECATION") internal fun deviceContext(context: Context): Map<String, Any?> = mapOf(
        "appVersion" to context.packageManager.getPackageInfo(context.packageName, 0).versionName,
        "os" to "Android ${Build.VERSION.RELEASE}", "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "locale" to Locale.getDefault().toLanguageTag(), "platform" to "android")
}
