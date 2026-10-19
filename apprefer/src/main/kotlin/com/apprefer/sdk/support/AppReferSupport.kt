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

/** How [AppReferSupport.headerStyle] draws the home screen's header. */
enum class AppReferSupportHeaderStyle {
    /** The accent with soft coloured glows (the default). */
    ACCENT,
    /** A flat neutral grey surface with the page's text colour: no accent, glow or colour. */
    PLAIN,
}

/** In-app support. Configure AppRefer before opening. Network methods are suspending. */
object AppReferSupport {
    /** Hero, bubble and button colour. Text on it turns dark automatically when the colour is bright. */
    var accentColor: Int = Color.rgb(120, 82, 255)
    /**
     * The home screen's header. [AppReferSupportHeaderStyle.ACCENT] (default) fills it with the
     * accent and soft coloured glows; [AppReferSupportHeaderStyle.PLAIN] is a flat neutral grey
     * with the page's text colour, for apps whose design has no colour accents. Bubbles and
     * buttons keep [accentColor] either way.
     */
    var headerStyle: AppReferSupportHeaderStyle = AppReferSupportHeaderStyle.ACCENT
    /** Line above the greeting. Defaults to "<App name> support" in the chat's language. */
    var title: String? = null
    /**
     * How fast the team usually answers, completing "We usually reply in …". Defaults to "under an
     * hour" in the chat's language. A value you set is used as written in every language, so set it
     * for the language your app is showing; in most languages the sentence around it leaves out
     * "in" (e.g. German "innerhalb einer Stunde").
     */
    var replyTime: String
        get() = customReplyTime ?: SupportStrings.text("support.replyTime.default")
        set(value) { customReplyTime = value }
    @Volatile private var customReplyTime: String? = null
    /**
     * Your own text for any of the chat's strings, by key (e.g.
     * `mapOf("support.home.subtitle" to "What's up?")`). Used before the SDK's translations, in
     * every language. Placeholders such as `{name}` are filled in; plural keys take `"key#one"`,
     * `"key#few"`, … or just `"key"` for every count. Keys: `Localization/support-strings.json`
     * in the SDK repo.
     */
    var strings: Map<String, String>
        get() = SupportStrings.overrides
        set(value) { SupportStrings.overrides = value.toMap() }
    /**
     * Forces the chat's language (e.g. "de", "pt-BR", "zh-Hant"). By default it follows the
     * language your app is running in, then the device's languages, then English.
     */
    var language: String?
        get() = SupportStrings.forcedLanguage
        set(value) { SupportStrings.forcedLanguage = value }
    /** First names of the people who answer (e.g. `listOf("Jay")`), shown on the help home. */
    var teamNames: List<String> = emptyList()
    /** The quick-help tiles on the home screen. Empty hides them. */
    var topics: List<AppReferSupportTopic> = AppReferSupportTopic.defaults
    /** The icon on the home screen. Defaults to the app's launcher icon, else its initials. */
    var appIcon: Drawable? = null
    /**
     * Opens your app's own paywall when the customer taps an offer card your team (or AI
     * assistant) sent in the chat. Set it once at startup:
     *
     * ```kotlin
     * AppReferSupport.onOffer = { offer -> PaywallActivity.start(context, offer.offeringId, offer.packageId) }
     * ```
     *
     * The card shows your offering's packages with Google Play's own prices; the SDK never shows a
     * paywall or starts a purchase itself. Without `onOffer`, cards say they can't be opened in
     * this version of the app. Runs on the main thread; with [closesChatOnOffer] (the default) the
     * chat Activity finishes first.
     */
    var onOffer: ((AppReferSupportOffer) -> Unit)? = null
        set(value) { field = value; SupportOffers.hasHandler = value != null }
    /** Close the chat before [onOffer] runs (default true). The embedded view calls its `onClose`. */
    var closesChatOnOffer: Boolean = true
    @Volatile private var offerQueue: SupportOfferReceipts? = null
    /** Receipts waiting to be sent, on disk per app. */
    internal fun offerReceipts(context: Context): SupportOfferReceipts =
        offerQueue ?: synchronized(this) { offerQueue ?: SupportOfferReceipts(SupportOfferPrefs(context)).also { offerQueue = it } }
    /** Hands a tapped card to [onOffer] on the main thread after [close]; false when the app set none. */
    internal fun deliver(offer: AppReferSupportOffer, close: () -> Unit): Boolean {
        val handler = onOffer ?: return false
        if (closesChatOnOffer) close()
        android.os.Handler(android.os.Looper.getMainLooper()).post { handler(offer) }
        return true
    }
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
        pendingClearIdentity = false
        val c = app ?: context
        if (c != null) {
            // Offer-card receipts belong to the session being retired; never sent for another account.
            offerReceipts(c).clear()
            AppReferStorage(c).clearUser()
            runCatching { c.cacheDir.listFiles()?.filter { it.name.startsWith("apprefer-support-") }?.forEach { it.delete() } }
            api(c, initialize = false).reset()
        }
        accountGeneration++
        withContext(Dispatchers.Main) { resetListeners.toList().reversed().forEach { it() } }
    }
    /**
     * Removes the account from Support without a full reset, e.g. when the user withdraws consent:
     * forgets the name, email and app user id, revokes the current Support session and starts a
     * new anonymous one. Conversations from the old session stay in your team inbox but are no
     * longer shown in the app. The app user id is not sent to Support again until it changes
     * (another account signs in) or `AppRefer.reset()` runs; the device id is not sent either.
     * A different user id stored later gets a fresh session (the cleared one is revoked). A
     * registered push token moves to the new session once the old one is revoked. Attribution,
     * Billing and the stored user id are untouched. Called before AppRefer has a Context, it is
     * applied as soon as one is available (configure or the first Support use).
     */
    suspend fun clearIdentity(context: Context? = com.apprefer.sdk.AppReferContext.context) = withContext(Dispatchers.IO) {
        scope.coroutineContext.cancelChildren()
        name = null; email = null
        val c = app ?: context
        if (c != null) {
            pendingClearIdentity = false
            offerReceipts(c).clear()
            AppReferStorage(c).supportIdentityCleared = true
            runCatching { c.cacheDir.listFiles()?.filter { it.name.startsWith("apprefer-support-") }?.forEach { it.delete() } }
        } else {
            // Never a silent no-op: applied (flag saved, session retired) as soon as a context appears.
            pendingClearIdentity = true
            android.util.Log.w("AppRefer", "support.clearIdentity() ran before AppRefer had a Context; it is applied on the next configure or Support use")
        }
        accountGeneration++
        if (resetListeners.isNotEmpty()) withContext(Dispatchers.Main) { resetListeners.toList().reversed().forEach { it() } }
        if (c != null) api(c, initialize = false).clearIdentity()
    }
    /** clearIdentity() ran with no Context in this process; applied by [applyPendingClear]. */
    @Volatile internal var pendingClearIdentity = false
    /** Applies a context-less clearIdentity(): saves the flag and retires the session synchronously. */
    private fun applyPendingClear(c: Context) {
        synchronized(this) { if (!pendingClearIdentity) return; pendingClearIdentity = false }
        AppReferStorage(c).supportIdentityCleared = true
        runCatching { c.cacheDir.listFiles()?.filter { it.name.startsWith("apprefer-support-") }?.forEach { it.delete() } }
        accountGeneration++
        val unmoved = api(c, initialize = false).retireForClearedIdentity()
        // No old session to wait for: the token goes straight onto the fresh anonymous session.
        if (unmoved != null) scope.launch { runCatching { api(c, initialize = false).register(unmoved) } }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Synchronized internal fun configure(context: Context) {
        if (app != null) return
        app = context.applicationContext
        com.apprefer.sdk.AppReferContext.context = app
        if (Build.VERSION.SDK_INT >= 26) {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel("apprefer_support", SupportStrings.text("support.notification.channel"), NotificationManager.IMPORTANCE_DEFAULT))
        }
        if (pendingClearIdentity) applyPendingClear(app!!)
    }
    internal fun api(context: Context, initialize: Boolean = true): SupportAPI {
        if (initialize) configure(context)
        val api = synchronized(this) {
            client ?: SupportAPI({ supportKey ?: AppRefer.currentApiKey() }, SecureSupportStore(context), { profile(context) },
                { deviceContext(context) }, identityCleared = { AppReferStorage(context).supportIdentityCleared }).also { client = it }
        }
        if (pendingClearIdentity) applyPendingClear(context)
        return api
    }
    /**
     * The session profile. The device id is left out after reset(), with collection off and in
     * Support-only apps; the app user id and the device id after clearIdentity() until the user
     * id changes (the device id would link the anonymous session back to the account).
     */
    internal fun profile(context: Context): Map<String, Any?> {
        val storage = AppReferStorage(context)
        val cleared = storage.supportIdentityCleared
        val userId = if (cleared) null else storage.getUserId()
        val deviceId = if (cleared) null else AppRefer.supportDeviceId()
        return linkedMapOf<String, Any?>("appUserId" to userId, "deviceId" to deviceId, "name" to name, "email" to email).filterValues { it != null }
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
    /**
     * Opens the help chat on one help article (e.g. from a "How does this work?" link). Back leads
     * to the help home; "Message us" starts a conversation about the article.
     */
    fun openArticle(slug: String) { app?.let { openArticle(it, slug) } }
    fun openArticle(context: Context, slug: String) { launch(context, null, null, article = slug) }
    /** Opens the help chat on the help centre: every article, with search. */
    fun openHelpCentre() { app?.let { openHelpCentre(it) } }
    fun openHelpCentre(context: Context) { launch(context, null, null, helpCentre = true) }
    private fun launch(context: Context, id: String?, draft: String?, article: String? = null, helpCentre: Boolean = false) {
        val intent = Intent(context, AppReferSupportActivity::class.java).putExtra("conversationId", id).putExtra("draft", draft)
            .putExtra("article", article).putExtra("helpCentre", helpCentre)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // MARK: Help centre and status

    /**
     * The app's published help articles in the chat's language ([language], else the app's
     * language), best first; with [query], search results (titles, summaries and bodies, up to 20).
     * Test SDK keys (`pk_test_`) also get drafts (`isDraft`). Lists are kept in memory for five
     * minutes; if a request fails, the last list for the same query is returned, and only without
     * one is the error thrown.
     */
    @JvmOverloads suspend fun articles(query: String? = null): List<AppReferHelpArticle> =
        api(app ?: error("Configure AppRefer first")).help.list(query, SupportStrings.language)

    /**
     * One help article with its markdown body, in the chat's language. Throws an
     * [AppReferSupportException] whose `isNotFound` is true (HTTP 404) when the article doesn't
     * exist or isn't published.
     */
    suspend fun article(slug: String): AppReferHelpArticleDetail =
        api(app ?: error("Configure AppRefer first")).help.article(slug, SupportStrings.language)

    /**
     * What Support can do for this app: whether reply notifications work on Android
     * (`pushReady`), whether customers can answer by email (`emailReplies`) and how many help
     * articles are published. Register push tokens only when `pushReady` is true. Cached as long
     * as the server allows (5 minutes once everything is verified, else 30 s).
     */
    suspend fun status(): AppReferSupportStatus = api(app ?: error("Configure AppRefer first")).help.status()

    /**
     * Confirms who the customer is, so your team and the AI assistant may use the account's
     * private details (plan, orders, credits). Your backend, after signing the user in, asks
     * AppRefer for a short-lived single-use token (`POST /api/v1/apps/{appId}/support/identity-tokens`
     * with your private key; never ship that key in the app) and passes it here. It verifies the
     * current Support session, so conversations already started on it count too. Call it after
     * `AppRefer.setUserId` with the same user; `reset()`, `clearIdentity()` or another account
     * ends it. Network failures are retried with the same token and then thrown (retrying the
     * same token later is safe); a refusal returns `verified = false` with a reason.
     */
    suspend fun verifyIdentity(identityToken: String): AppReferSupportIdentityVerification =
        api(app ?: error("Configure AppRefer first")).verifyIdentity(identityToken)

    /** The customer's email from identify(), for the "we'll email you" line. */
    internal val customerEmail: String? get() = email
    suspend fun registerPushToken(token: String) { val c = app ?: error("Configure AppRefer first"); api(c).register(token) }
    suspend fun removePushToken(token: String) { val c = app ?: error("Configure AppRefer first"); api(c).remove(token) }
    fun handleNotification(data: Map<String, String>): Boolean {
        if (data["type"] != "support_reply" || app == null) return false
        open(data["conversationId"]); return true
    }
    suspend fun unreadCount(): Int = app?.let { api(it).conversations().sumOf { c -> c.unread } } ?: 0
    internal fun appName(context: Context): String = context.applicationInfo.loadLabel(context.packageManager).toString()
    internal fun resolvedTitle(context: Context): String = title ?: SupportStrings.text("support.home.title", "app" to appName(context))
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
 *
 * The built-in tiles ([billing], [restore], [bug], [idea]) show their text in the chat's language;
 * text you pass is shown as written.
 */
class AppReferSupportTopic private constructor(
    /** "billing", "restore", "bug" or "idea" for the built-in tiles. */
    private val builtIn: String?,
    private val customTitle: String?,
    private val customSubtitle: String?,
    private val customPrompt: String?,
    /** A drawable resource, tinted with [tint]. */
    val icon: Int,
    /** ARGB colour of the icon and the topic chip. */
    val tint: Int,
    val action: (() -> Unit)?,
) {
    @JvmOverloads constructor(
        title: String,
        subtitle: String,
        icon: Int,
        tint: Int,
        prompt: String,
        action: (() -> Unit)? = null,
    ) : this(null, title, subtitle, prompt, icon, tint, action)

    /** Stable id: "billing", "restore", "bug" or "idea" for the built-in tiles, else the title you set. */
    val id: String get() = builtIn ?: customTitle.orEmpty()
    /** The tile's title; also the new conversation's subject. */
    val title: String get() = customTitle ?: SupportStrings.text("support.topic.$builtIn.title")
    val subtitle: String get() = customSubtitle ?: SupportStrings.text("support.topic.$builtIn.subtitle")
    /** The hint in the message field once the tile is picked. */
    val prompt: String get() = customPrompt ?: SupportStrings.text("support.topic.$builtIn.prompt")

    /** A changed copy. Text you leave out keeps following the chat's language on a built-in tile. */
    @JvmOverloads fun copy(
        title: String? = null,
        subtitle: String? = null,
        icon: Int = this.icon,
        tint: Int = this.tint,
        prompt: String? = null,
        action: (() -> Unit)? = this.action,
    ): AppReferSupportTopic = AppReferSupportTopic(builtIn, title ?: customTitle, subtitle ?: customSubtitle, prompt ?: customPrompt, icon, tint, action)

    override fun equals(other: Any?): Boolean = other is AppReferSupportTopic && other.builtIn == builtIn &&
        other.customTitle == customTitle && other.customSubtitle == customSubtitle && other.customPrompt == customPrompt &&
        other.icon == icon && other.tint == tint && other.action == action

    override fun hashCode(): Int = listOf(builtIn, customTitle, customSubtitle, customPrompt, icon, tint, action).hashCode()

    override fun toString(): String = "AppReferSupportTopic(id=$id, title=$title)"

    companion object {
        @JvmField val billing = AppReferSupportTopic("billing", null, null, null, R.drawable.apprefer_support_card, 0xFF6E4AFF.toInt(), null)
        @JvmField val restore = AppReferSupportTopic("restore", null, null, null, R.drawable.apprefer_support_restore, 0xFF2E8FFA.toInt(), null)
        @JvmField val bug = AppReferSupportTopic("bug", null, null, null, R.drawable.apprefer_support_bug, 0xFFF04554.toInt(), null)
        @JvmField val idea = AppReferSupportTopic("idea", null, null, null, R.drawable.apprefer_support_idea, 0xFF1FB866.toInt(), null)
        @JvmStatic val defaults: List<AppReferSupportTopic> get() = listOf(billing, restore, bug, idea)
    }
}
