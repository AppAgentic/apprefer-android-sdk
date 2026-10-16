package com.apprefer.sdk.support

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.os.Build
import com.apprefer.sdk.AppRefer
import com.apprefer.sdk.R
import com.apprefer.sdk.internal.AppReferStorage
import kotlinx.coroutines.*
import java.util.Locale

/** In-app support. Configure AppRefer before opening. Network methods are suspending. */
object AppReferSupport {
    /** Hero, bubble and button colour. Text on it turns dark automatically when the colour is bright. */
    var accentColor: Int = Color.rgb(120, 82, 255)
    /** Line above the greeting. Defaults to "<App name> support". */
    var title: String? = null
    /** How fast the team usually answers, completing "We usually reply in …". */
    var replyTime: String = "under an hour"
    /** First names of the people who answer (e.g. `listOf("Jay")`), shown on the help home. */
    var teamNames: List<String> = emptyList()
    /** The quick-help tiles on the home screen. Empty hides them. */
    var topics: List<AppReferSupportTopic> = AppReferSupportTopic.defaults
    /** The icon on the home screen. Defaults to the app's launcher icon, else its initials. */
    var appIcon: Drawable? = null
    @Volatile internal var name: String? = null
    @Volatile private var email: String? = null
    @Volatile private var app: Context? = null
    private var client: SupportAPI? = null
    @Volatile private var supportKey: String? = null
    internal val resetListeners = java.util.concurrent.CopyOnWriteArraySet<() -> Unit>()
    fun configure(context: Context, apiKey: String) { supportKey = apiKey; configure(context); resumeRevocations() }
    internal fun resumeRevocations() { (app ?: com.apprefer.sdk.AppReferContext.context)?.let { c -> scope.launch { runCatching { api(c, initialize = false).resumeRevocations() } } } }
    internal fun setUserId(userId: String) { (app ?: com.apprefer.sdk.AppReferContext.context)?.let { AppReferStorage(it).setUserId(userId) } }
    @Volatile internal var accountGeneration = 0
    suspend fun reset(context: Context? = com.apprefer.sdk.AppReferContext.context) = withContext(Dispatchers.IO) {
        scope.coroutineContext.cancelChildren()
        name = null; email = null
        val c = app ?: context
        if (c != null) {
            AppReferStorage(c).clearUser()
            runCatching { c.cacheDir.listFiles()?.filter { it.name.startsWith("apprefer-support-") }?.forEach { it.delete() } }
            api(c, initialize = false).reset()
        }
        accountGeneration++
        withContext(Dispatchers.Main) { resetListeners.toList().reversed().forEach { it() } }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized internal fun configure(context: Context) {
        if (app != null) return
        app = context.applicationContext
        com.apprefer.sdk.AppReferContext.context = app
        if (Build.VERSION.SDK_INT >= 26) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("apprefer_support", "Support replies", NotificationManager.IMPORTANCE_DEFAULT))
        }
    }
    internal fun api(context: Context, initialize: Boolean = true): SupportAPI {
        if (initialize) configure(context)
        return synchronized(this) {
            client ?: SupportAPI({ supportKey ?: AppRefer.currentApiKey() }, SecureSupportStore(context), { profile(context) },
                { deviceContext(context) }).also { client = it }
        }
    }
    /** The session profile. The device id is left out after reset(), with collection off and in Support-only apps. */
    internal fun profile(context: Context): Map<String, Any?> {
        val storage = AppReferStorage(context)
        return linkedMapOf<String, Any?>("appUserId" to storage.getUserId(), "deviceId" to AppRefer.supportDeviceId(), "name" to name, "email" to email).filterValues { it != null }
    }
    fun identify(name: String? = null, email: String? = null) {
        this.name = name?.trim()?.takeIf { it.isNotEmpty() }; this.email = email?.trim()?.takeIf { it.isNotEmpty() }
        identityChanged()
    }
    internal fun identityChanged() { app?.let { c -> scope.launch { try { api(c).syncIdentity() } catch (_: Exception) {} } } }
    @JvmOverloads fun open(conversationId: String? = null) { app?.let { open(it, conversationId) } }
    @JvmOverloads fun open(context: Context, conversationId: String? = null) { launch(context, conversationId, null) }
    /** Opens the help home with the message field focused and optionally pre-filled. */
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
    internal fun appName(context: Context): String = context.applicationInfo.loadLabel(context.packageManager).toString()
    internal fun resolvedTitle(context: Context): String = title ?: "${appName(context)} support"
    /** The override, else the launcher icon (null when the app has none, so initials are shown). */
    internal fun resolvedIcon(context: Context): Drawable? = appIcon ?: runCatching {
        if (context.applicationInfo.icon == 0) null else context.packageManager.getApplicationIcon(context.applicationInfo)
    }.getOrNull()
    @Suppress("DEPRECATION") internal fun deviceContext(context: Context): Map<String, Any?> = mapOf(
        "appVersion" to context.packageManager.getPackageInfo(context.packageName, 0).versionName,
        "os" to "Android ${Build.VERSION.RELEASE}", "device" to "${Build.MANUFACTURER} ${Build.MODEL}",
        "locale" to Locale.getDefault().toLanguageTag(), "platform" to "android")
}

/**
 * A quick-help tile on the home screen. Tapping it starts a message on that topic (its title becomes
 * the conversation subject), or runs [action] instead (e.g. your own restore-purchases flow).
 */
data class AppReferSupportTopic @JvmOverloads constructor(
    val title: String,
    val subtitle: String,
    /** A drawable resource, tinted with [tint]. */
    val icon: Int,
    /** ARGB colour of the icon and the topic chip. */
    val tint: Int,
    /** The hint in the message field once the tile is picked. */
    val prompt: String,
    val action: (() -> Unit)? = null,
) {
    companion object {
        @JvmField val billing = AppReferSupportTopic("Billing & refunds", "Charges, plans, receipts", R.drawable.apprefer_support_card, 0xFF6E4AFF.toInt(), "What happened with the charge?")
        @JvmField val restore = AppReferSupportTopic("Restore purchase", "Get your plan back", R.drawable.apprefer_support_restore, 0xFF2E8FFA.toInt(), "Which plan did you buy, and on which account?")
        @JvmField val bug = AppReferSupportTopic("Report a bug", "Screenshots welcome", R.drawable.apprefer_support_bug, 0xFFF04554.toInt(), "What went wrong? A screenshot helps.")
        @JvmField val idea = AppReferSupportTopic("Suggest an idea", "We read every one", R.drawable.apprefer_support_idea, 0xFF1FB866.toInt(), "What would make the app better for you?")
        @JvmStatic val defaults: List<AppReferSupportTopic> get() = listOf(billing, restore, bug, idea)
    }
}
