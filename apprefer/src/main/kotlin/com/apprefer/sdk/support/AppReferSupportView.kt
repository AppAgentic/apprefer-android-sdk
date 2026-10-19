package com.apprefer.sdk.support

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.apprefer.sdk.R
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

/** Full-screen host. The view can also be embedded in any native layout. */
class AppReferSupportActivity : Activity() {
    private lateinit var chat: AppReferSupportView
    private val resetListener: () -> Unit = { finish() }
    override fun onDestroy() { AppReferSupport.resetListeners.remove(resetListener); super.onDestroy() }
    private val night get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION") // Bar colours and decor fitting are still needed below API 35.
    override fun onCreate(state: Bundle?) {
        setTheme(if (night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        AppReferSupport.resetListeners.add(resetListener)
        val destination = intent.getStringExtra("article")?.let { AppReferSupportDestination.Article(it) }
            ?: if (intent.getBooleanExtra("helpCentre", false)) AppReferSupportDestination.HelpCentre else null
        chat = AppReferSupportView(this, intent.getStringExtra("conversationId"), intent.getStringExtra("draft"), destination)
        chat.onClose = { finish() }
        chat.onPickImages = {
            val pick = if (Build.VERSION.SDK_INT >= 33) Intent("android.provider.action.PICK_IMAGES").apply {
                type = "image/*"; if (it > 1) putExtra("android.provider.extra.PICK_IMAGES_MAX", it)
            } else Intent(Intent.ACTION_OPEN_DOCUMENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE); putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true) }
            @Suppress("DEPRECATION")
            startActivityForResult(pick, 8041)
        }
        state?.getString("appreferSupportState")?.let { chat.restoreDraft(it) }
        setContentView(chat)
        // Edge to edge: the hero runs under the status bar, the view pads for bars and keyboard itself.
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = chat.backgroundColor
        @Suppress("DEPRECATION") window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            chat.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                val ime = insets.getInsets(WindowInsets.Type.ime())
                chat.applyInsets(bars.left, bars.top, bars.right, max(bars.bottom, ime.bottom))
                insets
            }
            if (!night) window.insetsController?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS, WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                (if (!night && Build.VERSION.SDK_INT >= 26) View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0)
            chat.setOnApplyWindowInsetsListener { _, insets ->
                @Suppress("DEPRECATION")
                chat.applyInsets(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
                insets
            }
        }
        chat.onStatusBarStyle = { darkIcons -> statusBarIcons(darkIcons) }
        chat.requestApplyInsets()
    }

    private fun statusBarIcons(dark: Boolean) {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.setSystemBarsAppearance(if (dark) WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS else 0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
        } else if (Build.VERSION.SDK_INT >= 23) {
            @Suppress("DEPRECATION") val flags = window.decorView.systemUiVisibility
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (dark) flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR else flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        if (::chat.isInitialized) outState.putString("appreferSupportState", chat.saveDraft())
        super.onSaveInstanceState(outState)
    }
    override fun onResume() { super.onResume(); if (::chat.isInitialized) chat.resume() }
    override fun onPause() { if (::chat.isInitialized) chat.pause(); super.onPause() }
    @Deprecated("Platform activity result bridge") override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 8041 || resultCode != RESULT_OK || data == null) return
        val uris = data.clipData?.let { clip -> (0 until clip.itemCount).map { clip.getItemAt(it).uri } } ?: listOfNotNull(data.data)
        chat.addImages(uris)
    }
    @Deprecated("Platform back bridge") override fun onBackPressed() { if (!chat.goBack()) super.onBackPressed() }
}

/** How long a portrait that failed to load waits before it is tried again. */
private const val PORTRAIT_RETRY_MS = 30_000L

/** The screen the help chat opens on. Back always leads to the help home. */
sealed class AppReferSupportDestination {
    object Home : AppReferSupportDestination()
    /** Every help article, with search. */
    object HelpCentre : AppReferSupportDestination()
    /** One help article by slug (any translation's slug works). */
    data class Article(val slug: String) : AppReferSupportDestination()
    data class Conversation(val id: String) : AppReferSupportDestination()
}

/**
 * Native embeddable help chat: the help home (with its message field and help articles), the help
 * centre, articles and conversations. Call pause/resume with the host lifecycle.
 */
class AppReferSupportView @JvmOverloads constructor(
    context: Context, conversationId: String? = null, composing: String? = null,
    /** Where to open; overrides [conversationId] when set. */
    destination: AppReferSupportDestination? = null,
) : FrameLayout(context) {
    var onClose: () -> Unit = {}
    var onPickImages: ((Int) -> Unit)? = null
    /** True when the status bar should use dark icons (set by the full-screen Activity). */
    internal var onStatusBarStyle: ((Boolean) -> Unit)? = null
        set(value) { field = value; lastStatusDark = null; updateChrome() }

    private val api = AppReferSupport.api(context.applicationContext)
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val c = SupportColors(dark)
    val backgroundColor: Int = c.background
    private var p = SupportPalette(AppReferSupport.accentColor)
    private var h = SupportHeaderLook(AppReferSupport.headerStyle, p, dark)
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var visible = false

    /** "home", "thread", "help" (help centre) or "article". */
    private var route = when (destination) {
        is AppReferSupportDestination.HelpCentre -> "help"
        is AppReferSupportDestination.Article -> "article"
        is AppReferSupportDestination.Conversation -> "thread"
        is AppReferSupportDestination.Home -> "home"
        null -> if (conversationId != null) "thread" else "home"
    }
    private var id = (destination as? AppReferSupportDestination.Conversation)?.id
        ?: conversationId.takeIf { destination == null }

    // Help centre: the home section's articles, the help centre screen and the article reader.
    private var articles = emptyList<AppReferHelpArticle>()
    private var articlesJob: Job? = null
    private var helpAll = emptyList<AppReferHelpArticle>()
    private var helpLoaded = false
    private var helpFailed = false
    private var helpQuery = ""
    /** null while the search field is empty. */
    private var helpResults: List<AppReferHelpArticle>? = null
    private var helpSearching = false
    private var helpSearchJob: Job? = null
    private var helpFocusSearch = false
    private var helpContent: LinearLayout? = null
    private var articleSlug: String? = (destination as? AppReferSupportDestination.Article)?.slug
    private var articleTitle = ""
    /** Where Back goes from the article: "home" or "help". */
    private var articleBack = "home"
    private var articleDetail: AppReferHelpArticleDetail? = null
    private var articleBlocks = emptyList<SupportMarkdownBlock>()
    private var articleFailed = false
    private var articleContent: LinearLayout? = null
    private var headerTrailing: FrameLayout? = null
    /** How the reply will reach the customer: notification, email or only in the chat. */
    private var notice = SupportReplyNotice.PUSH
    private var subject = SupportStrings.text("support.conversation.defaultSubject")
    private val timeline = SupportTimeline { date(it) }
    // Offer cards: each card's latest state, store prices by offering then package, the card opening.
    private val offerCards = mutableMapOf<String, AppReferSupportOfferCard>()
    private var offerPrices = emptyMap<String, Map<String, SupportOfferPrice>>()
    private var openingCard: String? = null
    private val shownCards = mutableSetOf<String>()
    private var lastOfferRefresh = 0L
    private val offerQueue get() = AppReferSupport.offerReceipts(context)
    internal var offerOpener = SupportOfferOpener(AppReferSupport.offerReceipts(context.applicationContext), send = { api.sendOfferReceipt(it) })
    private val billingListener: () -> Unit = { post { scope.launch { refreshOffers() } } }
    // Review cards: each card's latest state by card id.
    private val reviewCards = mutableMapOf<String, AppReferSupportReviewCard>()
    /** Opens a validated Google Play URL (tests swap it); the system URL opener, no extra dependency. */
    internal var openStorePage: (String) -> Unit = { url -> openPlayListing(url) }
    private val cursor get() = timeline.cursor
    private val messages get() = timeline.messages
    private var threadLoaded = false
    private var conversations = emptyList<AppReferSupportConversation>()
    private var homeLoaded = false
    private var loadError: String? = null

    // The field on the current screen.
    private var draft = composing ?: ""
    private val images = mutableListOf<ByteArray>()
    private var topic: AppReferSupportTopic? = null
    private var sendError: String? = null
    private var focusOnOpen = composing != null
    /** The same on every retry of a new conversation, so the server never stores it twice. */
    private var homeClientMessageId = UUID.randomUUID().toString()
    private var starting = false
    private val pending = mutableListOf<SupportPending>()
    private var homeStash: HomeStash? = null
    private class HomeStash(val draft: String, val images: List<ByteArray>, val topic: AppReferSupportTopic?, val error: String?)
    private var draftFile: java.io.File? = null
    private var accountReset = false
    private val resetListener: () -> Unit = {
        accountReset = true
        pause(); scope.cancel()
        timeline.reset(); conversations = emptyList(); pending.clear()
        offerCards.clear(); offerPrices = emptyMap(); openingCard = null; shownCards.clear(); reviewCards.clear()
        images.clear(); draft = ""; homeStash = null; id = null; topic = null
        draftFile?.delete(); draftFile = null
        starting = false; sendError = null; loadError = null; route = "home"
        articleSlug = null; articleDetail = null; articleBlocks = emptyList(); helpQuery = ""; helpResults = null; helpSearching = false
        notice = SupportReplyNotice.PUSH
        homeClientMessageId = UUID.randomUUID().toString()
        shownPersona = null; headerKey = null; shownQuickReplies = null; renderedCount = -1
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        removeAllViews(); render()
        accountReset = false
        if (isAttachedToWindow) resume()
    }

    private val accountLifecycle = SupportAccountLifecycle({ AppReferSupport.accountGeneration }, AppReferSupport.resetListeners, resetListener)

    private var insetL = 0; private var insetT = 0; private var insetR = 0; private var insetB = 0
    private lateinit var scroll: SupportScrollView
    private lateinit var body: LinearLayout
    private lateinit var field: EditText
    private lateinit var accessories: LinearLayout
    private lateinit var composerBox: LinearLayout
    private lateinit var sendButton: FrameLayout
    private lateinit var sendIcon: ImageView
    private lateinit var sendSpinner: ProgressBar
    private lateinit var scrim: View
    private var hero: View? = null
    private var header: View? = null
    private var headerLabels: FrameLayout? = null
    private var headerAvatar: FrameLayout? = null
    private var headerKey: String? = null
    private var presenceHolder: FrameLayout? = null
    /** The AI assistant the screen currently shows. */
    private var shownPersona: AppReferSupportPersona? = null
    /** Portrait URL → when it last failed; tried again after [PORTRAIT_RETRY_MS]. */
    private val failedPortraits = mutableMapOf<String, Long>()
    private var shownQuickReplies: Boolean? = null
    private var renderedCount = -1
    private var lastStatusDark: Boolean? = null
    private val thumbs = WeakHashMap<ByteArray, Bitmap>()

    init { setBackgroundColor(backgroundColor); render() }

    /** The chat's text for [key] (see [SupportStrings]). */
    private fun s(key: String, vararg arguments: Pair<String, String>) = SupportStrings.text(key, *arguments)
    private val rtl get() = SupportStrings.isRightToLeft

    // MARK: - Building blocks

    private fun dp(n: Number) = (resources.displayMetrics.density * n.toFloat()).toInt()
    private fun dpf(n: Number) = resources.displayMetrics.density * n.toFloat()
    private fun font(weight: Int): Typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, weight, false) else when {
        weight >= 800 -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        weight >= 700 -> Typeface.DEFAULT_BOLD
        weight >= 500 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
        else -> Typeface.DEFAULT
    }
    private fun text(value: String, size: Float, color: Int = c.ink, weight: Int = 400, lines: Int = 0) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_SP, size); setTextColor(color); typeface = font(weight); includeFontPadding = false
        textLocale = SupportStrings.locale
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }
    private fun label(value: String) = text(value.uppercase(SupportStrings.locale), 12f, c.muted, 700).apply { letterSpacing = 0.05f }
    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    private fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
    private fun lp(w: Int = -1, h: Int = -2, top: Int = 0, start: Int = 0, end: Int = 0, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h, weight).apply { topMargin = top; marginStart = start; marginEnd = end }
    private fun rounded(color: Int, radius: Number, strokeColor: Int? = null, stroke: Number = 1) = GradientDrawable().apply {
        setColor(color); cornerRadius = dpf(radius); strokeColor?.let { setStroke(dp(stroke), it) }
    }
    private fun oval(color: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); strokeColor?.let { setStroke(dp(1), it) }
    }
    private fun gradient(radii: FloatArray? = null, oval: Boolean = false) = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.light, p.deep)).apply {
        if (oval) shape = GradientDrawable.OVAL else cornerRadii = radii
    }
    /**
     * Per-corner radii in dp: top-start, top-end, bottom-end, bottom-start. In a right-to-left chat
     * the corners swap sides, so a bubble's tail stays on the side it points to.
     */
    private fun corners(ts: Number, te: Number, be: Number, bs: Number): FloatArray {
        val (tl, tr, br, bl) = if (rtl) listOf(te, ts, bs, be) else listOf(ts, te, be, bs)
        return floatArrayOf(dpf(tl), dpf(tl), dpf(tr), dpf(tr), dpf(br), dpf(br), dpf(bl), dpf(bl))
    }
    private fun shadow(v: View, elevation: Number, alpha: Float) {
        v.elevation = dpf(elevation)
        if (Build.VERSION.SDK_INT >= 28) { v.outlineSpotShadowColor = supportAlpha(c.shadow, alpha); v.outlineAmbientShadowColor = supportAlpha(c.shadow, alpha) }
    }
    private fun icon(res: Int, tint: Int, size: Number) = ImageView(context).apply {
        setImageResource(res); setColorFilter(tint); layoutParams = FrameLayout.LayoutParams(dp(size), dp(size), Gravity.CENTER)
    }
    private fun circleButton(res: Int, description: String, size: Number, iconSize: Number, tint: Int, bg: Drawable, action: () -> Unit) = FrameLayout(context).apply {
        background = bg; addView(icon(res, tint, iconSize)); contentDescription = description
        isClickable = true; isFocusable = true; setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }
    private fun avatar(name: String?, size: Number, colors: IntArray, icon: Int? = null, ring: Boolean = false, imageUrl: String? = null, ringColor: Int = 0xE6FFFFFF.toInt()) =
        SupportAvatarView(context, colors, name?.let { SupportText.initial(it) }, icon, ring, ringColor).apply {
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            imageUrl?.let { url -> portrait(url) { image = it } }
        }
    private val agentColors get() = intArrayOf(p.warm, p.hot)
    /** Uses the shared image cache; a portrait that failed is tried again after 30 s (the initial shows meanwhile). */
    private fun portrait(url: String, show: (Bitmap) -> Unit) {
        SupportImages.cached(url)?.let { show(it); return }
        failedPortraits[url]?.let { if (System.currentTimeMillis() - it < PORTRAIT_RETRY_MS) return }
        scope.launch {
            try { show(withContext(Dispatchers.IO) { SupportImages.remote(url) }); failedPortraits.remove(url) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failedPortraits[url] = System.currentTimeMillis() }
        }
    }
    /** The persona as last read from AppRefer (shared by every help screen). */
    private val persona get() = api.persona
    /** The small "AI" tag on everything the AI assistant says. */
    private fun aiTag() = text(s("support.ai.tag"), 10f, p.accent, 900).apply {
        letterSpacing = 0.04f; setPadding(dp(6), dp(2), dp(6), dp(2))
        background = rounded(supportAlpha(p.accent, .12f), 6); contentDescription = s("support.a11y.aiAssistant")
    }
    private fun badge(count: Int) = text("$count", 12f, p.onAccent, 700).apply {
        background = rounded(p.accent, 99); setPadding(dp(8), dp(3), dp(8), dp(3)); contentDescription = SupportStrings.plural("support.a11y.unread", count)
    }

    /** Springy press like the iOS chat; the click still fires. */
    @SuppressLint("ClickableViewAccessibility")
    private fun View.pressable(description: String? = null, action: () -> Unit) {
        isClickable = true; isFocusable = true; description?.let { contentDescription = it }
        setOnClickListener { action() }
        setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(.97f).scaleY(.97f).setDuration(90).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(160).start()
            }
            false
        }
    }
    private fun ripple() = RippleDrawable(ColorStateList.valueOf(supportAlpha(c.ink, .08f)), null, ColorDrawable(Color.WHITE))

    private fun title() = AppReferSupport.resolvedTitle(context)
    private fun appName() = AppReferSupport.appName(context)

    // MARK: - Screens

    private fun render() {
        p = SupportPalette(AppReferSupport.accentColor)
        h = SupportHeaderLook(AppReferSupport.headerStyle, p, dark)
        // Arabic and Hebrew lay the chat out right to left (needs android:supportsRtl in the app).
        layoutDirection = if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        removeAllViews(); hero = null; header = null; headerLabels = null; headerAvatar = null; headerKey = null; presenceHolder = null; shownQuickReplies = null; renderedCount = -1; lastStatusDark = null
        helpContent = null; articleContent = null; headerTrailing = null
        shownPersona = persona
        when (route) {
            "home" -> buildHome()
            "help" -> buildHelp()
            "article" -> buildArticle()
            else -> buildThread()
        }
        applyInsets(insetL, insetT, insetR, insetB)
        updateChrome()
    }

    private fun buildHome() {
        scroll = SupportScrollView(context)
        val page = column()
        hero = buildHero().also { page.addView(it, lp()) }
        body = column().apply { setPadding(dp(16), 0, dp(16), dp(140)) }
        page.addView(body, lp(top = -dp(62)))
        scroll.addView(page, FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
        scrim = View(context).apply { setBackgroundColor(backgroundColor); alpha = 0f }
        addView(scrim, LayoutParams(-1, 0, Gravity.TOP))
        composerBox = buildComposer(SupportText.homeHint(topic?.prompt, persona?.name), R.drawable.apprefer_support_camera) { startConversation() }
        addView(composerBox, LayoutParams(-1, -2, Gravity.BOTTOM))
        composerBox.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) post { if (route == "home") body.setPadding(dp(16), 0, dp(16), max(dp(140), composerBox.height + dp(16))) }
        }
        renderHomeContent(); renderAccessories(); updateSend()
    }

    private fun buildHero(): View {
        val box = column().apply { background = SupportHeroDrawable(p, h, resources.displayMetrics.density) }
        val top = row().apply { gravity = Gravity.TOP }
        top.addView(appIconView())
        top.addView(Space(context), lp(0, 1, weight = 1f))
        top.addView(circleButton(R.drawable.apprefer_support_close, s("support.a11y.close"), 40, 18, h.foreground,
            oval(h.closeFill, h.closeStroke)) { if (!goBack()) onClose() })
        box.addView(top, lp())
        box.addView(text(title(), 15f, supportAlpha(h.foreground, .85f), 600, 1), lp(top = dp(20)))
        box.addView(text(SupportText.greeting(AppReferSupport.name), 38f, h.foreground, 800).apply {
            letterSpacing = -0.026f; maxLines = 1
            if (Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(22, 38, 1, TypedValue.COMPLEX_UNIT_SP) else ellipsize = TextUtils.TruncateAt.END
        }, lp(top = dp(4)))
        box.addView(text(s("support.home.subtitle"), 19f, supportAlpha(h.foreground, .92f), 500), lp(top = dp(6)))
        presenceHolder = FrameLayout(context).apply { clipChildren = false; addView(presence()) }.also { box.addView(it, lp(-2, top = dp(18))) }
        return box
    }

    /** A new or changed AI assistant from the latest read: refresh what shows it, keeping the field and scroll. */
    private fun personaChanged() {
        if (persona == shownPersona) return
        shownPersona = persona
        if (route == "home") {
            presenceHolder?.apply { removeAllViews(); addView(presence()) }
            renderHomeContent(); renderAccessories()
        } else renderMessages()
    }

    private fun appIconView(): View {
        val size = dp(46)
        val drawable = AppReferSupport.resolvedIcon(context)
        val view: View = if (drawable != null) ImageView(context).apply {
            setImageBitmap(iconBitmap(drawable, size)); scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dpf(13)) }
            }
            clipToOutline = true
        } else text(SupportText.initials(appName()), 15f, h.iconText, 800).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, h.iconTile).apply { cornerRadius = dpf(13) }
        }
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        view.elevation = dpf(6)
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        return view
    }

    /** Launcher icons are often adaptive: draw both layers so the art fills the rounded tile. */
    private fun iconBitmap(source: Drawable, size: Int): Bitmap {
        val d = source.constantState?.newDrawable(resources)?.mutate() ?: source.mutate()
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (Build.VERSION.SDK_INT >= 26 && d is AdaptiveIconDrawable) {
            val inset = size / 4 // 108 dp layers, 72 dp visible
            listOfNotNull(d.background, d.foreground).forEach { it.setBounds(-inset, -inset, size + inset, size + inset); it.draw(canvas) }
        } else { d.setBounds(0, 0, size, size); d.draw(canvas) }
        return bitmap
    }

    private fun presence(): View {
        val persona = persona
        val names = AppReferSupport.teamNames.take(if (persona == null) 3 else 2)
        val pill = row().apply {
            setPaddingRelative(dp(8), dp(7), dp(14), dp(7))
            background = rounded(h.pillFill, 22, h.pillStroke)
        }
        // The AI assistant first (with its portrait), then the team.
        val faces = mutableListOf<View>()
        val ring = h.avatarRing
        persona?.let { faces.add(avatar(it.name, 28, h.avatars ?: agentColors, ring = true, imageUrl = it.avatarUrl, ringColor = ring)) }
        names.forEachIndexed { i, n -> faces.add(avatar(n, 28, h.avatars ?: avatarColors(i), ring = true, ringColor = ring)) }
        if (faces.isEmpty()) faces.add(avatar(null, 28, h.avatars ?: agentColors, R.drawable.apprefer_support_bubble, ring = true, ringColor = ring))
        val stack = FrameLayout(context)
        val count = faces.size
        // Added last-first so the first face sits on top, like the iOS pill.
        for (i in count - 1 downTo 0) stack.addView(faces[i], FrameLayout.LayoutParams(dp(28), dp(28)).apply { marginStart = dp(20) * i })
        pill.addView(stack, lp(dp(28) + dp(20) * (count - 1), dp(28)))
        val line = SupportText.presence(AppReferSupport.teamNames, AppReferSupport.replyTime, persona?.name)
        pill.addView(text(line, 14f, h.foreground, 500).apply { maxLines = 2 }, lp(-2, start = dp(10)))
        pill.contentDescription = line
        return pill
    }

    private fun avatarColors(i: Int) = when (i % 3) {
        0 -> agentColors
        1 -> intArrayOf(0xFF5CADFF.toInt(), 0xFF2E73F2.toInt())
        else -> intArrayOf(0xFF4D4D4D.toInt(), 0xFF141414.toInt())
    }

    private fun renderHomeContent() {
        if (route != "home") return
        body.removeAllViews()
        val sections = SupportHomeSections(conversations)
        fun add(v: View) = body.addView(v, lp(top = if (body.childCount > 0) dp(24) else 0))
        sections.current?.let { add(continueCard(it)) }
        // Hidden entirely when the app has no published articles.
        if (articles.isNotEmpty()) add(helpSection(prominent = sections.current == null))
        if (AppReferSupport.topics.isNotEmpty()) add(quickHelp(sections.current != null || articles.isNotEmpty()))
        if (sections.others.isNotEmpty()) add(pastConversations(sections.others))
        if (!homeLoaded && conversations.isEmpty()) add(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(p.accent) }.let {
            FrameLayout(context).apply { addView(it, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)) }
        })
        loadError?.takeIf { conversations.isEmpty() && homeLoaded }?.let { add(text(it, 13f, c.muted).apply { setPadding(dp(6), 0, dp(6), 0) }) }
    }

    private fun continueCard(conversation: AppReferSupportConversation): View {
        val card = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(c.card, 26) }
        shadow(card, 12, .22f)
        card.addView(label(s("support.home.continue")))
        val line = row()
        val a = SupportContinueAvatar(conversation, persona, AppReferSupport.teamNames, h, p)
        line.addView(avatar(a.name, 46, a.colors, if (a.chatIcon) R.drawable.apprefer_support_forum else null, imageUrl = a.imageUrl))
        val labels = column()
        labels.addView(text(conversation.subject, 16f, c.ink, 600, 1))
        labels.addView(text(if (conversation.lastFrom == "customer") s("support.home.youPrefix", "text" to conversation.preview) else conversation.preview, 14f, c.muted, lines = 1), lp(top = dp(3)))
        line.addView(labels, lp(0, weight = 1f, start = dp(12)))
        line.addView(if (conversation.unread > 0) badge(conversation.unread) else text(time(conversation.lastMessageAt), 12f, c.muted), lp(-2, start = dp(6)))
        card.addView(line, lp(top = dp(12)))
        card.pressable(s("support.a11y.continueConversation", "subject" to conversation.subject)) { openThread(conversation) }
        return card
    }

    private fun quickHelp(showLabel: Boolean): View {
        val box = column()
        if (showLabel) box.addView(label(s("support.home.quickHelp")).apply { setPaddingRelative(dp(6), 0, 0, 0) }, lp())
        AppReferSupport.topics.chunked(2).forEachIndexed { r, pair ->
            val line = row().apply { gravity = Gravity.TOP }
            pair.forEachIndexed { i, t -> line.addView(tile(t), lp(0, weight = 1f, start = if (i == 1) dp(10) else 0)) }
            if (pair.size == 1) line.addView(Space(context), lp(0, 1, weight = 1f, start = dp(10)))
            box.addView(line, lp(top = if (r > 0 || showLabel) dp(10) else 0))
        }
        return box
    }

    private fun tile(t: AppReferSupportTopic): View {
        val tile = column().apply { setPadding(dp(14), dp(14), dp(14), dp(14)); background = rounded(c.card, 20) }
        shadow(tile, 3, .07f)
        tile.addView(FrameLayout(context).apply {
            background = rounded(supportAlpha(t.tint, .13f), 11); addView(icon(t.icon, t.tint, 18))
        }, lp(dp(34), dp(34)))
        tile.addView(text(t.title, 15f, c.ink, 600, 1), lp(top = dp(10)))
        tile.addView(text(t.subtitle, 12.5f, c.muted, lines = 1), lp(top = dp(3)))
        tile.pressable("${t.title}, ${t.subtitle}") { pick(t) }
        return tile
    }

    private fun pastConversations(list: List<AppReferSupportConversation>): View {
        val box = column()
        box.addView(label(s("support.home.conversations")).apply { setPaddingRelative(dp(6), 0, 0, 0) })
        val card = column().apply { background = rounded(c.card, 20); clipToOutline = true }
        shadow(card, 3, .07f)
        list.forEachIndexed { i, conversation ->
            if (i > 0) card.addView(View(context).apply { setBackgroundColor(c.divider) }, lp(h = max(1, dp(.5f)), start = dp(64)))
            card.addView(conversationRow(conversation))
        }
        box.addView(card, lp(top = dp(10)))
        return box
    }

    private fun conversationRow(conversation: AppReferSupportConversation): View {
        val open = conversation.status != "closed"
        val line = row().apply { setPadding(dp(12), dp(11), dp(12), dp(11)); background = ripple() }
        line.addView(FrameLayout(context).apply {
            background = oval(supportAlpha(p.accent, .13f))
            addView(icon(if (open) R.drawable.apprefer_support_bubble else R.drawable.apprefer_support_check, p.accent, 16))
        }, lp(dp(40), dp(40)))
        val labels = column()
        labels.addView(text(conversation.subject, 15f, c.ink, if (conversation.unread > 0) 600 else 400, 1))
        labels.addView(text(conversation.preview, 13.5f, c.muted, lines = 1), lp(top = dp(3)))
        line.addView(labels, lp(0, weight = 1f, start = dp(12)))
        line.addView(if (conversation.unread > 0) badge(conversation.unread).apply { setPadding(dp(7), dp(3), dp(7), dp(3)) } else text(time(conversation.lastMessageAt), 12f, c.muted), lp(-2, start = dp(8)))
        line.isClickable = true; line.isFocusable = true
        line.contentDescription = s("support.a11y.conversation", "subject" to conversation.subject)
        line.setOnClickListener { openThread(conversation) }
        return line
    }

    private fun buildThread() {
        scroll = SupportScrollView(context)
        body = column().apply { setPadding(dp(14), dp(86), dp(14), dp(12)) }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
        scrim = View(context).apply { setBackgroundColor(supportAlpha(backgroundColor, .96f)) }
        addView(scrim, LayoutParams(-1, 0, Gravity.TOP))
        header = buildHeader().also { addView(it, LayoutParams(-1, dp(64), Gravity.TOP).apply { setMargins(dp(12), dp(10), dp(12), 0) }) }
        composerBox = buildComposer(s("support.composer.placeholder.thread"), R.drawable.apprefer_support_add) { sendTyped() }
        addView(composerBox, LayoutParams(-1, -2, Gravity.BOTTOM))
        composerBox.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) post {
                if (route == "thread") { body.setPadding(dp(14), dp(86) + insetT, dp(14), composerBox.height + dp(12)); scroll.stickToBottom() }
            }
        }
        renderMessages(); renderAccessories(); updateSend()
        loadNotice()
    }

    /** How the team's reply will reach the customer (status from AppRefer, cached by the server's rules). */
    private fun loadNotice() {
        scope.launch {
            val status = try { api.help.status() } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            val next = SupportReplyNotice.choose(status, api.hasPushToken, AppReferSupport.customerEmail != null)
            if (next != notice) { notice = next; if (route == "thread") renderMessages() }
        }
    }

    private fun buildHeader(): View {
        val bar = row().apply { setPadding(dp(10), 0, dp(10), 0); background = rounded(c.card, 32) }
        shadow(bar, 8, .14f)
        bar.addView(circleButton(R.drawable.apprefer_support_back, s("support.a11y.back"), 42, 18, p.accent, oval(supportAlpha(c.ink, .05f))) { goBack() })
        headerAvatar = FrameLayout(context).also { bar.addView(it, lp(dp(42), dp(42), start = dp(10))) }
        headerLabels = FrameLayout(context).also { bar.addView(it, lp(0, weight = 1f, start = dp(10))) }
        return bar
    }

    /**
     * The person answering: the newest team reply's author. Until someone on the team has replied,
     * the AI assistant (when the app has one) is who's answering.
     */
    private fun updateHeader() {
        val agent = messages.lastOrNull { it.from == "team" && SupportText.firstName(it.authorName) != null }?.let { SupportText.firstName(it.authorName) }
        val persona = persona.takeIf { agent == null }
        val key = "$agent|$persona"
        if (headerKey == key) return
        headerKey = key
        val labels = column()
        val face = if (persona != null) {
            labels.addView(row().apply {
                // Capped so the AI tag always shows, even with a long name and large text.
                addView(text(persona.name, 16f, c.ink, 700, 1).apply { maxWidth = dp(150); ellipsize = android.text.TextUtils.TruncateAt.END }, lp(-2))
                addView(aiTag(), lp(-2, start = dp(6)))
            }, lp(-2))
            labels.addView(text(s("support.thread.personaSubtitle", "app" to appName()), 12.5f, c.muted, lines = 1), lp(top = dp(2)))
            avatar(persona.name, 42, agentColors, imageUrl = persona.avatarUrl)
        } else {
            labels.addView(text(agent ?: s("support.thread.team", "app" to appName()), 16f, c.ink, 700, 1))
            labels.addView(text(s("support.thread.replyTime", "time" to AppReferSupport.replyTime), 12.5f, c.muted, lines = 1), lp(top = dp(2)))
            avatar(agent, 42, agentColors, if (agent == null) R.drawable.apprefer_support_forum else null)
        }
        headerAvatar?.apply { removeAllViews(); addView(face, FrameLayout.LayoutParams(-1, -1)) }
        headerLabels?.apply { removeAllViews(); addView(labels, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL)) }
    }

    private fun showsTime(i: Int): Boolean {
        if (i !in messages.indices) return false
        if (i == 0) return true
        val a = date(messages[i - 1].createdAt); val b = date(messages[i].createdAt)
        return a > 0 && b > 0 && b - a > 3_600_000
    }
    private fun sameSide(a: Int, b: Int) = a in messages.indices && b in messages.indices && messages[a].from == messages[b].from
    private val pendingHere get() = pending.filter { it.conversationId == id }

    private fun renderMessages() {
        if (route != "thread") return
        body.removeAllViews()
        val waiting = pendingHere
        if (!threadLoaded && messages.isEmpty() && waiting.isEmpty()) {
            if (loadError != null) {
                body.addView(text(loadError ?: "", 14f, c.muted).apply { gravity = Gravity.CENTER }, lp(top = dp(30)))
                body.addView(FrameLayout(context).apply {
                    addView(text(s("support.action.tryAgain"), 15f, p.accent, 600).apply {
                        setPadding(dp(16), dp(10), dp(16), dp(10)); background = rounded(c.card, 99, supportAlpha(p.accent, .18f))
                        pressable { loadError = null; renderMessages(); if (visible) startPolling() }
                    }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL))
                }, lp(top = dp(12)))
            } else body.addView(FrameLayout(context).apply {
                addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(p.accent) }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            }, lp(top = dp(30)))
        }
        messages.forEachIndexed { i, m ->
            val timed = showsTime(i)
            if (timed) body.addView(text(time(m.createdAt), 12f, c.muted, 600).apply { gravity = Gravity.CENTER }, lp().apply {
                topMargin = if (i == 0) dp(4) else dp(16); bottomMargin = dp(8)
            })
            val last = i == messages.size - 1 || !sameSide(i, i + 1) || showsTime(i + 1)
            val bubble = when {
                m.from == "customer" -> customerBubble(m, last)
                m.from == "bot" && SupportText.firstName(m.authorName) != null -> personaMessage(m, requireNotNull(SupportText.firstName(m.authorName)), last)
                m.from == "bot" -> botCard(m)
                else -> teamBubble(m, last)
            }
            val card = m.offer?.let { offerCards[it.cardId] ?: it }
            val review = if (card == null) m.review?.let { reviewCards[it.cardId] ?: it } else null
            val view = if (card != null) column().apply {
                if (m.text.isNotEmpty() || m.from == "bot") addView(bubble, lp())
                addView(offerCard(card), lp(top = if (m.text.isNotEmpty()) dp(8) else 0))
            } else if (review != null) column().apply {
                if (m.text.isNotEmpty() || m.from == "bot") addView(bubble, lp())
                addView(reviewCard(review), lp(top = if (m.text.isNotEmpty()) dp(8) else 0))
            } else bubble
            body.addView(view, lp(top = if (i > 0 && !timed) (if (sameSide(i - 1, i)) dp(3) else dp(10)) else 0))
        }
        waiting.forEach { body.addView(pendingBubble(it), lp(top = dp(6))) }
        val last = messages.lastOrNull()
        if (threadLoaded && waiting.isEmpty() && last?.from == "customer") {
            body.addView(text(notice.text(clock(last.createdAt)), 11.5f, c.muted, 500).apply {
                gravity = Gravity.END; setPaddingRelative(0, 0, dp(4), 0)
            }, lp(top = dp(5)))
        }
        updateHeader()
        val count = messages.size + waiting.size
        if (count != renderedCount) { scroll.scrollToBottom(animated = renderedCount > 0); renderedCount = count }
        refreshQuickReplies()
    }

    /**
     * An offer card under a team or AI message: heading, the packages with Google Play's prices
     * (and "Current plan"), and one button that hands the offer to the app's paywall. Disabled
     * cards say why. Never a price, discount or deadline of the SDK's own.
     */
    private fun offerCard(card: AppReferSupportOfferCard): View {
        val look = SupportOfferLook.of(card, SupportOffers.hasHandler)
        val active = look == SupportOfferLook.OPEN
        val box = column().apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(c.card, 22, if (active) supportAlpha(p.accent, .22f) else supportAlpha(c.ink, .06f))
            if (look == SupportOfferLook.UNAVAILABLE) alpha = .85f
        }
        shadow(box, 3, .07f)
        val tint = if (active) p.accent else c.muted
        box.addView(row().apply {
            addView(FrameLayout(context).apply {
                background = oval(supportAlpha(tint, .13f)); addView(icon(R.drawable.apprefer_support_tag, tint, 14))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp(dp(26), dp(26)))
            addView(text(s("support.offer.title"), 15f, c.ink, 700).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, lp(-2, start = dp(8)))
        }, lp())
        val rows = if (card.status == "unavailable") emptyList() else card.shownPackages
        if (rows.isNotEmpty()) {
            val list = column().apply { setPadding(dp(12), 0, dp(12), 0); background = rounded(supportAlpha(c.ink, if (dark) .06f else .035f), 14) }
            val prices = offerPrices[card.offeringId].orEmpty()
            rows.forEachIndexed { i, pkg ->
                if (i > 0) list.addView(View(context).apply { setBackgroundColor(c.divider) }, lp(-1, 1))
                val price = prices[pkg.id]
                val name = SupportOfferText.label(pkg, price?.name)
                list.addView(row().apply {
                    setPadding(0, dp(11), 0, dp(11))
                    addView(text(name, 15f, c.ink, 500, 2), lp(0, weight = 1f))
                    val tag = SupportOfferText.ownedTag(pkg, price)
                    val priceText = price?.let { SupportOfferText.price(it) }
                    if (tag != null) addView(text(tag, 11.5f, p.accent, 600).apply {
                        setPadding(dp(8), dp(3), dp(8), dp(3)); background = rounded(supportAlpha(p.accent, .12f), 99)
                    }, lp(-2, start = dp(8)))
                    priceText?.let { addView(text(it, 15f, c.ink, 600).apply { fontFeatureSettings = "tnum" }, lp(-2, start = dp(8))) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
                    contentDescription = listOfNotNull(name, tag, priceText).joinToString(", ")
                }, lp())
            }
            box.addView(list, lp(top = dp(12)))
        }
        val notice = look.notice
        if (notice != null) {
            box.addView(row().apply {
                gravity = Gravity.TOP
                addView(icon(if (look == SupportOfferLook.OWNED) R.drawable.apprefer_support_check else R.drawable.apprefer_support_info, c.muted, 14).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { topMargin = dp(2) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(text(notice, 13.5f, c.muted), lp(-2, start = dp(6)))
            }, lp(top = dp(12)))
        } else {
            val opening = openingCard == card.cardId
            box.addView(FrameLayout(context).apply {
                background = gradient(floatArrayOf(dpf(99), dpf(99), dpf(99), dpf(99), dpf(99), dpf(99), dpf(99), dpf(99)))
                addView(row().apply {
                    gravity = Gravity.CENTER
                    if (opening) addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(p.onAccent) }, lp(dp(18), dp(18), end = dp(8)))
                    addView(text(s("support.offer.open"), 15.5f, p.onAccent, 600), lp(-2))
                }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                minimumHeight = dp(46)
                isEnabled = !opening
                pressable("${s("support.offer.open")}. ${s("support.offer.a11y.hint")}") { openOffer(card) }
            }, lp(top = dp(12), h = dp(46)))
        }
        if (shownCards.add(card.cardId)) {
            offerQueue.enqueue(SupportOfferReceipt(id ?: "", card.cardId, "shown", SupportOfferReceipt.shownId(card.cardId), SupportOfferReceipt.iso(System.currentTimeMillis())))
            scope.launch { runCatching { api.flushOfferReceipts(offerQueue) } }
        }
        return FrameLayout(context).apply {
            setPaddingRelative(dp(38), 0, 0, 0)
            addView(box, FrameLayout.LayoutParams(minOf(dp(340), resources.displayMetrics.widthPixels - dp(66)), -2, Gravity.START))
        }
    }

    /** "View offer": checked with the server, then handed to the app's paywall. A refusal redraws instead. */
    private fun openOffer(card: AppReferSupportOfferCard) {
        val conversation = id ?: return
        if (openingCard != null || !card.isAvailable || !SupportOffers.hasHandler) return
        val epoch = api.actionGeneration
        openingCard = card.cardId; renderMessages()
        scope.launch {
            val outcome = try { offerOpener.check(offerOpener.receipt(card, conversation)) } finally { openingCard = null }
            if (epoch != api.actionGeneration || id != conversation) return@launch
            when (outcome) {
                is SupportOfferOpenCheck.Refused -> { refreshOffers(force = true); renderMessages(); return@launch }
                is SupportOfferOpenCheck.Recorded -> outcome.card?.let { offerCards[it.cardId] = it }
                is SupportOfferOpenCheck.Offline -> {}
            }
            renderMessages()
            AppReferSupport.deliver(AppReferSupportOffer(card.cardId, conversation, card.offeringId, card.packageId)) { onClose() }
        }
    }

    /**
     * A review card under a team message: heading, one neutral line naming the store, and "Write a
     * review", which opens the app's Google Play listing. No stars, no rating question.
     */
    private fun reviewCard(card: AppReferSupportReviewCard): View {
        val look = SupportReviewLook.of(card)
        val active = look is SupportReviewLook.Open
        val store = SupportReviewLink.storeKey(card.store ?: "play_store")
        val box = column().apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(c.card, 22, if (active) supportAlpha(p.accent, .22f) else supportAlpha(c.ink, .06f))
            if (!active) alpha = .85f
        }
        shadow(box, 3, .07f)
        val tint = if (active) p.accent else c.muted
        box.addView(row().apply {
            addView(FrameLayout(context).apply {
                background = oval(supportAlpha(tint, .13f)); addView(icon(R.drawable.apprefer_support_edit, tint, 14))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, lp(dp(26), dp(26)))
            addView(text(s("support.review.title"), 15f, c.ink, 700).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, lp(-2, start = dp(8)))
        }, lp())
        if (look is SupportReviewLook.Open) {
            box.addView(text(s("support.review.body.$store"), 14.5f, c.ink), lp(top = dp(12)))
            val opening = openingCard == card.cardId
            box.addView(FrameLayout(context).apply {
                background = gradient(floatArrayOf(dpf(99), dpf(99), dpf(99), dpf(99), dpf(99), dpf(99), dpf(99), dpf(99)))
                addView(row().apply {
                    gravity = Gravity.CENTER
                    if (opening) addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(p.onAccent) }, lp(dp(18), dp(18), end = dp(8)))
                    addView(text(s("support.review.open"), 15.5f, p.onAccent, 600), lp(-2))
                }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
                minimumHeight = dp(46)
                isEnabled = !opening
                pressable("${s("support.review.open")}. ${s("support.review.a11y.hint.$store")}") { openReview(card) }
            }, lp(top = dp(12), h = dp(46)))
        } else {
            box.addView(row().apply {
                gravity = Gravity.TOP
                addView(icon(R.drawable.apprefer_support_info, c.muted, 14).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { topMargin = dp(2) }
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                })
                addView(text(s(if (look is SupportReviewLook.Unavailable) "support.review.unavailable" else "support.review.unsupported"), 13.5f, c.muted), lp(-2, start = dp(6)))
            }, lp(top = dp(12)))
        }
        if (shownCards.add(card.cardId)) {
            offerQueue.enqueue(SupportOfferReceipt(id ?: "", card.cardId, "shown", SupportOfferReceipt.shownId(card.cardId), SupportOfferReceipt.iso(System.currentTimeMillis())))
            scope.launch { runCatching { api.flushOfferReceipts(offerQueue) } }
        }
        return FrameLayout(context).apply {
            setPaddingRelative(dp(38), 0, 0, 0)
            addView(box, FrameLayout.LayoutParams(minOf(dp(340), resources.displayMetrics.widthPixels - dp(66)), -2, Gravity.START))
        }
    }

    /** "Write a review": checked with the server (at most 3 s), then the listing opens. A refusal redraws instead. */
    private fun openReview(card: AppReferSupportReviewCard) {
        val conversation = id ?: return
        val look = SupportReviewLook.of(card) as? SupportReviewLook.Open ?: return
        if (openingCard != null) return
        val epoch = api.actionGeneration
        openingCard = card.cardId; renderMessages()
        scope.launch {
            val outcome = try { offerOpener.check(offerOpener.receipt(card.cardId, conversation)) } finally { openingCard = null }
            if (epoch != api.actionGeneration || id != conversation) return@launch
            if (outcome is SupportOfferOpenCheck.Refused) { refreshReviews(); renderMessages(); return@launch }
            renderMessages()
            openStorePage(look.url)
        }
    }

    /** The Play Store app when it's there, else whatever opens https links. */
    private fun openPlayListing(url: String) {
        val uri = android.net.Uri.parse(url)
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        try { context.startActivity(android.content.Intent(intent).setPackage("com.android.vending")) }
        catch (_: android.content.ActivityNotFoundException) { runCatching { context.startActivity(intent) } }
    }

    private fun absorbReviews(incoming: List<AppReferSupportMessage>) { incoming.mapNotNull { it.review }.forEach { reviewCards[it.cardId] = it } }

    private suspend fun refreshReviews() {
        val conversation = id ?: return
        if (route != "thread" || reviewCards.isEmpty()) return
        val cards = try { api.reviewCards(conversation) } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        if (id != conversation) return
        val changed = cards.any { reviewCards[it.cardId] != it }
        cards.forEach { reviewCards[it.cardId] = it }
        if (changed) renderMessages()
    }

    /** New cards from a read; prices for their offerings come from AppRefer Billing when the app uses it. */
    private fun absorbOffers(incoming: List<AppReferSupportMessage>) {
        absorbReviews(incoming)
        val cards = incoming.mapNotNull { it.offer }
        if (cards.isEmpty()) return
        cards.forEach { offerCards[it.cardId] = it }
        scope.launch { loadOfferPrices() }
    }

    @OptIn(com.apprefer.sdk.InternalAppReferApi::class)
    private suspend fun loadOfferPrices() {
        val source = com.apprefer.sdk.AppReferInternal.supportOfferPrices ?: return
        val ids = offerCards.values.map { it.offeringId }.toSet()
        val loaded = try { source(ids).mapValues { (_, m) -> m.mapValues { SupportOfferPrice(it.value.price, it.value.name, it.value.period, it.value.oneTime) } } }
            catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        if (loaded != offerPrices) { offerPrices = loaded; if (route == "thread") renderMessages() }
    }

    /**
     * Fresh states for every card here, after a purchase, restore, sign-in, catalogue change or
     * coming back to the app (and every 30 s while the chat is open); also re-reads store prices.
     */
    private suspend fun refreshOffers(force: Boolean = true) {
        val conversation = id ?: return
        if (route != "thread" || (offerCards.isEmpty() && reviewCards.isEmpty())) return
        if (!force && System.currentTimeMillis() - lastOfferRefresh < 30_000) return
        lastOfferRefresh = System.currentTimeMillis()
        refreshReviews()
        if (offerCards.isEmpty()) return
        val cards = try { api.offerCards(conversation) } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
        if (id != conversation) return
        val changed = cards.any { offerCards[it.cardId] != it }
        cards.forEach { offerCards[it.cardId] = it }
        if (changed) renderMessages()
        loadOfferPrices()
    }

    private fun bubbleText(value: String, color: Int) = text(value, 16f, color).apply {
        setLineSpacing(dpf(2), 1f); setPadding(dp(15), dp(11), dp(15), dp(11)); setTextIsSelectable(true)
    }

    private fun thumbnails(urls: List<AppReferSupportAttachment>, end: Boolean): View {
        val strip = SupportFlowRow(context, dp(6), end)
        urls.forEach { a ->
            val (frame, picture) = framedImage(74, 112, 14)
            picture.contentDescription = a.name ?: s("support.a11y.screenshot")
            a.url?.let { url ->
                frame.pressable(a.name ?: s("support.a11y.screenshot")) { viewImage(url) }
                val cached = SupportImages.cached(url)
                if (cached != null) picture.setImageBitmap(cached)
                else scope.launch {
                    try { picture.setImageBitmap(withContext(Dispatchers.IO) { SupportImages.remote(url) }) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { picture.contentDescription = s("support.a11y.imageUnavailable") }
                }
            } ?: run { picture.contentDescription = s("support.a11y.imageUnavailable") }
            strip.addView(frame, ViewGroup.LayoutParams(dp(74), dp(112)))
        }
        return strip
    }

    /** A picture with a white border, rounded corners and a soft shadow. */
    private fun framedImage(w: Int, h: Int, radius: Int): Pair<FrameLayout, ImageView> {
        val picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(supportAlpha(c.ink, .06f))
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dpf(radius - 3)) }
            }
            clipToOutline = true
        }
        val frame = FrameLayout(context).apply {
            background = rounded(Color.WHITE, radius); setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(picture, FrameLayout.LayoutParams(-1, -1))
            layoutParams = LinearLayout.LayoutParams(dp(w), dp(h))
        }
        shadow(frame, 3, .12f)
        return frame to picture
    }

    private fun customerBubble(m: AppReferSupportMessage, tail: Boolean): View {
        val box = column().apply { gravity = Gravity.END; setPaddingRelative(dp(60), 0, 0, 0) }
        val shots = m.attachments.filter { it.contentType?.startsWith("image/") == true }
        if (shots.isNotEmpty()) box.addView(thumbnails(shots, true), lp(-2))
        if (m.text.isNotEmpty()) box.addView(bubbleText(m.text, p.onAccent).apply {
            background = gradient(corners(22, 22, if (tail) 6 else 22, 22))
        }, lp(-2, top = if (shots.isNotEmpty()) dp(6) else 0))
        return box
    }

    private fun teamBubble(m: AppReferSupportMessage, showAvatar: Boolean): View {
        val line = row().apply { gravity = Gravity.BOTTOM; setPaddingRelative(0, 0, dp(40), 0) }
        if (showAvatar) line.addView(avatar(SupportText.firstName(m.authorName), 30, agentColors))
        else line.addView(Space(context), lp(dp(30), dp(30)))
        val box = column()
        val shots = m.attachments.filter { it.contentType?.startsWith("image/") == true }
        if (shots.isNotEmpty()) box.addView(thumbnails(shots, false), lp(-2))
        if (m.text.isNotEmpty()) box.addView(bubbleText(m.text, c.ink).apply {
            background = GradientDrawable().apply { setColor(c.card); cornerRadii = corners(22, 22, 22, if (showAvatar) 6 else 22) }
            shadow(this, 2, .06f)
        }, lp(-2, top = if (shots.isNotEmpty()) dp(6) else 0))
        line.addView(box, lp(-2, start = dp(8)))
        return line
    }

    private fun botCard(m: AppReferSupportMessage): View {
        val line = row().apply { setPaddingRelative(0, 0, dp(50), 0) }
        val card = column().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(c.card, 20) }
        shadow(card, 2, .06f)
        card.addView(text("✦ " + s("support.thread.instantAnswer").uppercase(SupportStrings.locale), 11.5f, p.accent, 700).apply { letterSpacing = 0.035f })
        card.addView(text(m.text, 15.5f, c.ink).apply { setLineSpacing(dpf(2), 1f); setTextIsSelectable(true) }, lp(-2, top = dp(4)))
        line.addView(card, lp(-2))
        return line
    }

    /** A message from the AI assistant: its portrait, name and "AI" tag, then the text; a hand-over adds who has it now. */
    private fun personaMessage(m: AppReferSupportMessage, name: String, last: Boolean): View {
        val persona = persona
        val showAvatar = last || m.isHandoff
        val line = row().apply { gravity = Gravity.BOTTOM; setPaddingRelative(0, 0, dp(40), 0) }
        if (showAvatar) line.addView(avatar(name, 30, agentColors, imageUrl = persona?.takeIf { it.name == name }?.avatarUrl))
        else line.addView(Space(context), lp(dp(30), dp(30)))
        val bubble = column().apply {
            setPadding(dp(15), dp(10), dp(15), dp(12))
            background = GradientDrawable().apply { setColor(c.card); cornerRadii = corners(22, 22, 22, if (showAvatar) 6 else 22) }
        }
        shadow(bubble, 2, .06f)
        bubble.addView(row().apply {
            addView(text(name, 13f, c.ink, 700, 1).apply { maxWidth = dp(140); ellipsize = android.text.TextUtils.TruncateAt.END }, lp(-2))
            addView(aiTag(), lp(-2, start = dp(6)))
        }, lp(-2))
        bubble.addView(text(m.text, 16f, c.ink).apply { setLineSpacing(dpf(2), 1f); setTextIsSelectable(true) }, lp(-2, top = dp(4)))
        line.addView(bubble, lp(-2, start = dp(8)))
        if (!m.isHandoff) return line
        val box = column()
        box.addView(line, lp())
        box.addView(handoffPill(persona?.handoffTo), lp(top = dp(10)))
        return box
    }

    /** Under the hand-over: who has the conversation now. */
    private fun handoffPill(to: String?): View {
        val pill = row().apply {
            setPaddingRelative(dp(5), dp(5), dp(12), dp(5)); background = rounded(c.card, 99, supportAlpha(c.muted, .18f))
        }
        pill.addView(avatar(to, 22, agentColors, if (to == null) R.drawable.apprefer_support_people else null))
        pill.addView(text(SupportText.handoff(to, AppReferSupport.replyTime), 12.5f, c.muted, 600, 2), lp(-2, start = dp(8)))
        return FrameLayout(context).apply { addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL)) }
    }

    private fun pendingBubble(pend: SupportPending): View {
        val box = column().apply { gravity = Gravity.END; setPaddingRelative(dp(60), 0, 0, 0) }
        if (pend.images.isNotEmpty()) {
            val strip = SupportFlowRow(context, dp(6), true).apply { alpha = .6f }
            pend.images.forEach { bytes ->
                val (frame, picture) = framedImage(74, 112, 14)
                picture.setImageBitmap(thumb(bytes))
                strip.addView(frame, ViewGroup.LayoutParams(dp(74), dp(112)))
            }
            box.addView(strip, lp(-2))
        }
        if (pend.text.isNotEmpty()) box.addView(bubbleText(pend.text, p.onAccent).apply {
            background = gradient(corners(22, 22, 6, 22)); alpha = .6f
        }, lp(-2, top = if (pend.images.isNotEmpty()) dp(5) else 0))
        box.addView(if (pend.failed) text(s("support.thread.notSent"), 12f, c.error, 600).apply {
            setPadding(0, dp(4), 0, dp(4)); setOnClickListener { deliver(pend) }; contentDescription = s("support.a11y.retryMessage")
        } else text(s("support.thread.sending"), 11.5f, c.muted, 500).apply { contentDescription = s("support.a11y.sendingMessage") }, lp(-2, top = dp(5)))
        return box
    }

    // MARK: - Help centre

    /** The home's article list: memory cache first (five minutes); offline keeps what was shown, never an error. */
    private fun refreshArticles() {
        if (articlesJob?.isActive == true) return
        articlesJob = scope.launch {
            val language = SupportStrings.language
            if (articles.isEmpty()) api.help.cachedList(language)?.takeIf { it.isNotEmpty() }?.let { cached ->
                articles = cached; if (route == "home") renderHomeContent()
            }
            val fresh = try { api.help.list(null, language) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (fresh != null && fresh != articles) { articles = fresh; if (route == "home") renderHomeContent() }
        }
    }

    /** "Help articles" on the help home: a search entry and the first three articles. */
    private fun helpSection(prominent: Boolean): View {
        val card = column().apply { setPadding(dp(16), dp(16), dp(16), dp(10)); background = rounded(c.card, 26) }
        if (prominent) shadow(card, 12, .22f) else shadow(card, 3, .07f)
        val top = row()
        top.addView(label(s("support.help.title")), lp(0, weight = 1f))
        if (articles.size > 3) top.addView(row().apply {
            setPaddingRelative(dp(8), dp(4), 0, dp(4))
            addView(text(s("support.help.seeAll"), 13.5f, p.accent, 600, 1), lp(-2))
            addView(icon(R.drawable.apprefer_support_chevron, p.accent, 12), lp(dp(12), dp(12), start = dp(3)))
            pressable(s("support.help.seeAll")) { openHelp(search = false) }
        }, lp(-2))
        card.addView(top, lp())
        val search = row().apply {
            setPaddingRelative(dp(14), 0, dp(14), 0); background = rounded(c.background, 14)
            addView(icon(R.drawable.apprefer_support_search, c.muted, 17), lp(dp(17), dp(17)))
            addView(text(s("support.help.search.placeholder"), 15.5f, c.muted, lines = 1), lp(0, weight = 1f, start = dp(9)))
            pressable(s("support.help.search.placeholder")) { openHelp(search = true) }
        }
        card.addView(search, lp(h = dp(44), top = dp(12)))
        val top3 = articles.take(3)
        top3.forEachIndexed { i, article ->
            if (i > 0) card.addView(View(context).apply { setBackgroundColor(c.divider) }, lp(h = max(1, dp(.5f)), start = dp(48)))
            card.addView(articleRow(article, summaryLines = 1, horizontal = 0), lp(top = if (i == 0) dp(4) else 0))
        }
        return card
    }

    /** One article in a list: a document tile, the title and its summary. */
    private fun articleRow(article: AppReferHelpArticle, summaryLines: Int, horizontal: Int): View {
        val line = row().apply { setPaddingRelative(dp(horizontal), dp(10), dp(horizontal), dp(10)); background = ripple() }
        line.addView(FrameLayout(context).apply {
            background = rounded(supportAlpha(p.accent, .12f), 11); addView(icon(R.drawable.apprefer_support_article, p.accent, 17))
        }, lp(dp(36), dp(36)))
        val labels = column()
        labels.addView(text(article.title, 15f, c.ink, 600, 2))
        if (article.isDraft) labels.addView(draftBadge(), lp(-2, top = dp(3)))
        if (article.summary.isNotEmpty()) labels.addView(text(article.summary, 13.5f, c.muted, lines = summaryLines), lp(top = dp(2)))
        line.addView(labels, lp(0, weight = 1f, start = dp(12)))
        line.addView(icon(R.drawable.apprefer_support_chevron, supportAlpha(c.muted, .7f), 12), lp(dp(12), dp(12), start = dp(6)))
        line.isClickable = true; line.isFocusable = true
        line.contentDescription = s("support.help.a11y.article", "title" to article.title) + if (article.summary.isNotEmpty()) ". ${article.summary}" else ""
        line.setOnClickListener { openArticle(article) }
        return line
    }

    private fun draftBadge() = text(s("support.help.draft").uppercase(SupportStrings.locale), 9.5f, 0xFFE07B00.toInt(), 800).apply {
        letterSpacing = 0.04f; setPadding(dp(6), dp(2), dp(6), dp(2)); background = rounded(supportAlpha(0xFFFF9500.toInt(), .14f), 6)
    }

    /** The floating bar at the top of the help screens (same as a conversation's). */
    private fun screenHeader(title: String): View {
        val bar = row().apply { setPadding(dp(10), 0, dp(10), 0); background = rounded(c.card, 32) }
        shadow(bar, 8, .14f)
        bar.addView(circleButton(R.drawable.apprefer_support_back, s("support.a11y.back"), 42, 18, p.accent, oval(supportAlpha(c.ink, .05f))) { goBack() })
        bar.addView(text(title, 17f, c.ink, 700, 1).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, lp(0, weight = 1f, start = dp(10)))
        headerTrailing = FrameLayout(context).also { bar.addView(it, lp(-2, dp(42))) }
        return bar
    }

    /** Scroll, top band and header shared by the help centre and the article. */
    private fun buildHelpFrame(title: String) {
        scroll = SupportScrollView(context)
        body = column().apply { setPadding(dp(16), dp(86), dp(16), dp(32)) }
        scroll.addView(body, FrameLayout.LayoutParams(-1, -2))
        addView(scroll, LayoutParams(-1, -1))
        scrim = View(context).apply { setBackgroundColor(supportAlpha(backgroundColor, .96f)) }
        addView(scrim, LayoutParams(-1, 0, Gravity.TOP))
        header = screenHeader(title).also { addView(it, LayoutParams(-1, dp(64), Gravity.TOP).apply { setMargins(dp(12), dp(10), dp(12), 0) }) }
    }

    /** "Still need help?" with the team's reply time and a "Message us" button. */
    private fun stillNeedHelpCard(subject: String?): View {
        val card = column().apply { setPadding(dp(18), dp(18), dp(18), dp(18)); background = rounded(c.card, 26) }
        shadow(card, 3, .07f)
        val persona = persona
        val top = row()
        val first = AppReferSupport.teamNames.firstOrNull()
        top.addView(if (persona != null) avatar(persona.name, 44, agentColors, imageUrl = persona.avatarUrl)
            else avatar(first, 44, agentColors, if (first == null) R.drawable.apprefer_support_bubble else null))
        val labels = column()
        labels.addView(text(s("support.help.stillNeedHelp"), 17f, c.ink, 700).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true })
        val subtitle = if (persona != null) s("support.presence.persona", "persona" to persona.name, "time" to AppReferSupport.replyTime)
            else s("support.presence.team", "time" to AppReferSupport.replyTime)
        labels.addView(text(subtitle, 13.5f, c.muted, lines = 2), lp(top = dp(2)))
        top.addView(labels, lp(0, weight = 1f, start = dp(12)))
        card.addView(top, lp())
        val button = row().apply {
            gravity = Gravity.CENTER; background = gradient(corners(25, 25, 25, 25))
            addView(icon(R.drawable.apprefer_support_bubble, p.onAccent, 16), lp(dp(16), dp(16)))
            addView(text(s("support.help.messageUs"), 16f, p.onAccent, 600, 1), lp(-2, start = dp(8)))
            pressable(s("support.help.messageUs")) { messageUs(subject) }
        }
        shadow(button, 6, .3f)
        card.addView(button, lp(h = dp(50), top = dp(14)))
        return card
    }

    private fun spinnerRow(message: String?): View = row().apply {
        gravity = Gravity.CENTER; setPadding(0, dp(24), 0, dp(24))
        addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(p.accent) }, lp(dp(24), dp(24)))
        message?.let { addView(text(it, 14f, c.muted), lp(-2, start = dp(10))) }
    }

    /** A centred message with an icon tile and, optionally, a Try again button. */
    private fun helpMessage(iconRes: Int, title: String, hint: String?, retry: (() -> Unit)?): View {
        val box = column().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(dp(12), dp(22), dp(12), dp(22)) }
        box.addView(FrameLayout(context).apply { background = oval(supportAlpha(p.accent, .12f)); addView(icon(iconRes, p.accent, 22)) }, lp(dp(56), dp(56)))
        box.addView(text(title, 16f, c.ink, 600).apply { gravity = Gravity.CENTER }, lp(-2, top = dp(10)))
        hint?.let { box.addView(text(it, 14f, c.muted).apply { gravity = Gravity.CENTER }, lp(-2, top = dp(6))) }
        retry?.let { action ->
            box.addView(text(s("support.action.tryAgain"), 15f, p.accent, 600).apply {
                setPadding(dp(16), dp(10), dp(16), dp(10)); background = rounded(c.card, 99, supportAlpha(p.accent, .18f))
                pressable(s("support.action.tryAgain"), action)
            }, lp(-2, top = dp(12)))
        }
        return box
    }

    private fun openHelp(search: Boolean) {
        if (starting) return
        helpFocusSearch = search
        navigate("help")
    }

    private fun openArticle(article: AppReferHelpArticle) {
        if (starting) return
        articleBack = if (route == "help") "help" else "home"
        articleSlug = article.slug; articleTitle = article.title
        articleDetail = null; articleBlocks = emptyList(); articleFailed = false
        navigate("article")
    }

    /** Back to the home's message field; from an article its title becomes the conversation subject. */
    private fun messageUs(subject: String?) {
        topic = subject?.takeIf { it.isNotBlank() }?.let {
            AppReferSupportTopic(it, "", R.drawable.apprefer_support_article, p.accent, s("support.help.prompt"))
        }
        navigate("home")
        focusField()
    }

    // The help centre screen: every article grouped by category, and search.

    private fun buildHelp() {
        buildHelpFrame(s("support.help.title"))
        val input = EditText(context).apply {
            setText(helpQuery); hint = s("support.help.search.placeholder"); setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTextColor(c.ink); setHintTextColor(supportAlpha(c.muted, .75f)); background = null; setSingleLine(true)
            setPadding(dp(8), 0, dp(4), 0); textLocale = SupportStrings.locale
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
            highlightColor = supportAlpha(p.accent, .3f)
            setOnEditorActionListener { _, action, _ -> if (action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH) { hideKeyboard(); true } else false }
        }
        val clear = circleButton(R.drawable.apprefer_support_close, s("support.help.a11y.clearSearch"), 36, 14, c.muted, ColorDrawable(Color.TRANSPARENT)) { input.setText("") }
        clear.visibility = if (helpQuery.isEmpty()) View.GONE else View.VISIBLE
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                val value = s?.toString().orEmpty()
                if (value == helpQuery) return
                clear.visibility = if (value.isEmpty()) View.GONE else View.VISIBLE
                searchHelp(value)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        val field = row().apply { setPaddingRelative(dp(14), 0, dp(6), 0); background = rounded(c.card, 18) }
        shadow(field, 3, .07f)
        field.addView(icon(R.drawable.apprefer_support_search, c.muted, 18), lp(dp(18), dp(18)))
        field.addView(input, lp(0, h = dp(50), weight = 1f))
        field.addView(clear, lp(dp(36), dp(36)))
        body.addView(field, lp(h = dp(50)))
        helpContent = column().also { body.addView(it, lp(top = dp(22))) }
        renderHelpContent()
        loadHelp()
        if (helpFocusSearch) {
            helpFocusSearch = false
            input.post {
                input.requestFocus(); input.setSelection(input.text.length)
                (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
    }

    private fun loadHelp() {
        scope.launch {
            val language = SupportStrings.language
            if (helpAll.isEmpty()) api.help.cachedList(language)?.let { helpAll = it; renderHelpContent() }
            try { helpAll = api.help.list(null, language); helpFailed = false }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { helpFailed = helpAll.isEmpty() }
            helpLoaded = true
            if (route == "help") renderHelpContent()
        }
    }

    /** Filters what's on screen at once, then asks the server (which also searches bodies) 300 ms after the last keystroke. */
    private fun searchHelp(text: String) {
        helpQuery = text
        helpSearchJob?.cancel()
        val q = SupportHelpClient.normalizedQuery(text)
        if (q == null) { helpResults = null; helpSearching = false; renderHelpContent(); return }
        helpResults = SupportHelpLogic.localMatches(helpAll, q); helpSearching = true
        renderHelpContent()
        val language = SupportStrings.language
        helpSearchJob = scope.launch {
            delay(300)
            val found = try { api.help.list(q, language) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (route != "help" || helpQuery != text) return@launch
            found?.let { helpResults = it }
            helpSearching = false
            renderHelpContent()
        }
    }

    private fun renderHelpContent() {
        val box = helpContent ?: return
        if (route != "help") return
        box.removeAllViews()
        fun add(v: View) = box.addView(v, lp(top = if (box.childCount > 0) dp(22) else 0))
        val results = helpResults
        when {
            results != null -> when {
                results.isNotEmpty() -> add(articleList(results))
                helpSearching -> add(spinnerRow(s("support.help.searching")))
                else -> add(helpMessage(R.drawable.apprefer_support_search, s("support.help.search.noResults", "query" to (SupportHelpClient.normalizedQuery(helpQuery) ?: "")), s("support.help.search.noResultsHint"), null))
            }
            helpAll.isEmpty() -> when {
                helpLoaded && helpFailed -> add(helpMessage(R.drawable.apprefer_support_article, s("support.help.listFailed"), null) {
                    helpLoaded = false; helpFailed = false; renderHelpContent(); loadHelp()
                })
                !helpLoaded -> add(spinnerRow(null))
            }
            else -> SupportHelpLogic.groups(helpAll).forEach { (title, list) ->
                add(column().apply {
                    title?.let { addView(label(it).apply { setPaddingRelative(dp(6), 0, 0, 0) }, lp()) }
                    addView(articleList(list), lp(top = if (title != null) dp(10) else 0))
                })
            }
        }
        if (helpLoaded || helpAll.isNotEmpty()) add(stillNeedHelpCard(null))
    }

    private fun articleList(list: List<AppReferHelpArticle>): View {
        val card = column().apply { setPadding(0, dp(4), 0, dp(4)); background = rounded(c.card, 22); clipToOutline = true }
        shadow(card, 3, .07f)
        list.forEachIndexed { i, article ->
            if (i > 0) card.addView(View(context).apply { setBackgroundColor(c.divider) }, lp(h = max(1, dp(.5f)), start = dp(62)))
            card.addView(articleRow(article, summaryLines = 2, horizontal = 14))
        }
        return card
    }

    // The article reader.

    private fun buildArticle() {
        buildHelpFrame(s("support.help.title"))
        articleContent = column().also { body.addView(it, lp()) }
        renderArticle()
        if (articleDetail == null) loadArticle()
    }

    private fun loadArticle() {
        val slug = articleSlug ?: return
        articleFailed = false
        scope.launch {
            try {
                val detail = api.help.article(slug, SupportStrings.language)
                val blocks = withContext(Dispatchers.Default) { SupportMarkdown.blocks(detail.body) }
                if (articleSlug == slug) { articleDetail = detail; articleBlocks = blocks }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { if (articleSlug == slug) articleFailed = articleDetail == null }
            if (route == "article" && articleSlug == slug) renderArticle()
        }
    }

    private fun renderArticle() {
        val box = articleContent ?: return
        box.removeAllViews()
        val detail = articleDetail
        val title = detail?.title ?: articleTitle
        detail?.category?.let { box.addView(text(it.uppercase(SupportStrings.locale), 12f, p.accent, 700).apply { letterSpacing = 0.05f }, lp(-2)) }
        if (title.isNotEmpty()) box.addView(text(title, 28f, c.ink, 800).apply {
            letterSpacing = -0.02f; if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }, lp(top = if (detail?.category != null) dp(8) else 0))
        if (detail != null) {
            val meta = row()
            detail.article.updatedAtMillis?.let { ms ->
                val day = java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, SupportStrings.locale).format(Date(ms))
                meta.addView(text(s("support.help.updated", "date" to day), 13f, c.muted, 500), lp(-2, end = dp(8)))
            }
            if (detail.isDraft) meta.addView(draftBadge(), lp(-2))
            if (meta.childCount > 0) box.addView(meta, lp(top = dp(8)))
        }
        val content: View = when {
            detail != null -> markdownView(articleBlocks).apply { setPadding(dp(20), dp(20), dp(20), dp(20)); background = rounded(c.card, 26) }.also { shadow(it, 3, .07f) }
            articleFailed -> helpMessage(R.drawable.apprefer_support_article, s("support.help.loadFailed"), null) { loadArticle(); renderArticle() }.apply { background = rounded(c.card, 26) }
            else -> spinnerRow(s("support.help.loading"))
        }
        box.addView(content, lp(top = dp(20)))
        box.addView(stillNeedHelpCard(title.takeIf { it.isNotEmpty() }), lp(top = dp(24)))
        // Open in browser, once the article is known to have a web page.
        headerTrailing?.apply {
            removeAllViews()
            detail?.article?.webUrl?.let { url ->
                addView(circleButton(R.drawable.apprefer_support_open, s("support.help.openInBrowser"), 42, 18, c.ink, oval(supportAlpha(c.ink, .05f))) { openExternal(url) },
                    FrameLayout.LayoutParams(dp(42), dp(42)))
            }
        }
    }

    /** Opens an http(s) or mailto link outside the app; anything else is ignored. */
    private fun openExternal(url: String) {
        val safe = SupportMarkdown.safeUrl(url) ?: return
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safe)).apply { if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        try { context.startActivity(intent) } catch (_: Exception) {}
    }

    // MARK: - Markdown

    /** Renders parsed article blocks. Links open in the browser; nothing in an article can run code. */
    private fun markdownView(blocks: List<SupportMarkdownBlock>): LinearLayout {
        val box = column()
        var previous: SupportMarkdownBlock? = null
        for (block in blocks) {
            val gap = when {
                previous == null -> 0
                previous is SupportMarkdownBlock.ListItem && block is SupportMarkdownBlock.ListItem -> dp(7)
                previous is SupportMarkdownBlock.Heading -> dp(8)
                block is SupportMarkdownBlock.Heading -> dp(22)
                else -> dp(14)
            }
            box.addView(blockView(block), lp(top = gap))
            previous = block
        }
        return box
    }

    private fun richText(value: SupportRichText, size: Float, color: Int = c.ink, weight: Int = 400): TextView {
        val spanned = android.text.SpannableStringBuilder(value.text)
        for (span in value.spans) {
            if (span.start < 0 || span.end > value.text.length || span.start >= span.end) continue
            val styles: List<Any> = when (span.style) {
                SupportInlineStyle.BOLD -> listOf(android.text.style.StyleSpan(Typeface.BOLD))
                SupportInlineStyle.ITALIC -> listOf(android.text.style.StyleSpan(Typeface.ITALIC))
                SupportInlineStyle.STRIKE -> listOf(android.text.style.StrikethroughSpan())
                SupportInlineStyle.CODE -> listOf(android.text.style.TypefaceSpan("monospace"), android.text.style.BackgroundColorSpan(supportAlpha(c.ink, .07f)))
                SupportInlineStyle.LINK -> span.url?.let { url -> listOf(object : android.text.style.ClickableSpan() {
                    override fun onClick(widget: View) { openExternal(url) }
                    override fun updateDrawState(ds: android.text.TextPaint) { ds.color = p.accent; ds.isUnderlineText = true }
                }) } ?: emptyList()
            }
            styles.forEach { spanned.setSpan(it, span.start, span.end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
        }
        return text("", size, color, weight).apply {
            text = spanned; setLineSpacing(dpf(3), 1f)
            if (value.links.isNotEmpty()) { movementMethod = android.text.method.LinkMovementMethod.getInstance(); highlightColor = supportAlpha(p.accent, .2f) }
            else setTextIsSelectable(true)
        }
    }

    private fun blockView(block: SupportMarkdownBlock): View = when (block) {
        is SupportMarkdownBlock.Heading -> richText(block.text, when (block.level) { 1 -> 23f; 2 -> 20f; 3 -> 17f; else -> 15.5f }, weight = if (block.level <= 2) 700 else 600).apply {
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        is SupportMarkdownBlock.Paragraph -> richText(block.text, 16f)
        is SupportMarkdownBlock.ListItem -> row().apply {
            gravity = Gravity.TOP
            setPaddingRelative(dp(20) * block.depth, 0, 0, 0)
            val marker: View = block.number?.let { n ->
                text("$n.", 15f, p.accent, 600).apply { gravity = Gravity.END; minWidth = dp(20); textDirection = View.TEXT_DIRECTION_LTR }
            } ?: View(context).apply { background = oval(supportAlpha(p.accent, if (block.depth == 0) 1f else .5f)) }
            if (block.number != null) addView(marker, lp(-2, top = dp(1)))
            else addView(marker, lp(dp(6), dp(6), top = dp(9), start = dp(2)))
            addView(richText(block.text, 16f), lp(0, weight = 1f, start = dp(10)))
        }
        is SupportMarkdownBlock.Quote -> row().apply {
            gravity = Gravity.TOP
            addView(View(context).apply { background = rounded(supportAlpha(p.accent, .6f), 2) }, lp(dp(4), -1))
            addView(richText(block.text, 15.5f, c.muted).apply { setTypeface(typeface, Typeface.ITALIC) }, lp(0, weight = 1f, start = dp(12)))
        }
        is SupportMarkdownBlock.Code -> HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false; layoutDirection = View.LAYOUT_DIRECTION_LTR
            background = rounded(supportAlpha(c.ink, .05f), 14)
            addView(TextView(context).apply {
                text = block.code; typeface = Typeface.MONOSPACE; setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f); setTextColor(c.ink)
                setPadding(dp(14), dp(14), dp(14), dp(14)); setTextIsSelectable(true); textDirection = View.TEXT_DIRECTION_LTR
            })
        }
        is SupportMarkdownBlock.Rule -> View(context).apply { setBackgroundColor(c.divider) }.let { rule ->
            FrameLayout(context).apply { setPadding(0, dp(4), 0, dp(4)); addView(rule, FrameLayout.LayoutParams(-1, max(1, dp(1)))) }
        }
        is SupportMarkdownBlock.Image -> ImageView(context).apply {
            adjustViewBounds = true; scaleType = ImageView.ScaleType.FIT_CENTER; maxHeight = dp(360)
            contentDescription = block.alt.ifEmpty { s("support.a11y.screenshot") }
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) { outline.setRoundRect(0, 0, view.width, view.height, dpf(14)) }
            }
            clipToOutline = true
            val picture = this
            SupportImages.cached(block.url)?.let { setImageBitmap(it) } ?: run {
                minimumHeight = dp(160); setBackgroundColor(supportAlpha(c.ink, .05f))
                scope.launch {
                    try {
                        picture.setImageBitmap(withContext(Dispatchers.IO) { SupportImages.remote(block.url) })
                        picture.minimumHeight = 0; picture.background = null
                    } catch (e: CancellationException) { throw e }
                    catch (_: Exception) { picture.visibility = View.GONE }
                }
            }
        }
    }

    // MARK: - Composer

    private fun buildComposer(placeholder: String, leading: Int, onSend: () -> Unit): LinearLayout {
        val box = column().apply { setPadding(0, 0, 0, dp(8)) }
        accessories = column()
        box.addView(accessories, lp())
        val bar = row().apply {
            gravity = Gravity.BOTTOM; setPaddingRelative(dp(6), dp(7), dp(7), dp(7)); background = rounded(c.card, 29)
        }
        shadow(bar, 10, .18f)
        bar.addView(circleButton(leading, s("support.a11y.addScreenshot"), 44, 22, c.muted, ColorDrawable(Color.TRANSPARENT)) { pick() })
        field = EditText(context).apply {
            setText(draft); hint = placeholder; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(c.ink); setHintTextColor(supportAlpha(c.muted, .75f))
            background = null; minLines = 1; maxLines = 5; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(4), dp(11), dp(4), dp(11)); minHeight = dp(44)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            highlightColor = supportAlpha(p.accent, .3f); textLocale = SupportStrings.locale
            if (Build.VERSION.SDK_INT >= 29) setTextCursorDrawable(GradientDrawable().apply { setColor(p.accent); setSize(dp(2), dp(1)) })
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    draft = s.toString(); updateSend(); if (route == "thread") refreshQuickReplies()
                }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        bar.addView(field, lp(0, weight = 1f, start = dp(2), end = dp(4)))
        sendIcon = icon(R.drawable.apprefer_support_send, Color.WHITE, 22)
        sendSpinner = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(p.onAccent); visibility = View.GONE; layoutParams = FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER) }
        sendButton = FrameLayout(context).apply {
            addView(sendIcon); addView(sendSpinner); contentDescription = s("support.a11y.send")
            isClickable = true; isFocusable = true; setOnClickListener { onSend() }
        }
        bar.addView(sendButton, lp(dp(44), dp(44)))
        box.addView(bar, lp().apply { marginStart = dp(14); marginEnd = dp(14) })
        return box
    }

    private fun updateSend() {
        if (!::sendButton.isInitialized) return
        val can = !starting && (draft.isNotBlank() || images.isNotEmpty())
        sendButton.isEnabled = can
        sendButton.background = if (can) gradient(oval = true) else oval(0x4D808080)
        sendIcon.setColorFilter(if (can) p.onAccent else Color.WHITE)
        val spinning = starting && route == "home"
        sendIcon.visibility = if (spinning) View.INVISIBLE else View.VISIBLE
        sendSpinner.visibility = if (spinning) View.VISIBLE else View.GONE
        if (spinning) sendButton.background = gradient(oval = true)
    }

    private val showsQuickReplies get() = route == "thread" && threadLoaded && pendingHere.isEmpty() && draft.isEmpty() && images.isEmpty() &&
        supportOffersQuickReplies(messages.lastOrNull())

    private fun refreshQuickReplies() { if (shownQuickReplies != showsQuickReplies) renderAccessories() }

    /** What sits above the bar: error, topic chip, quick replies and picked screenshots. */
    private fun renderAccessories() {
        if (!::accessories.isInitialized) return
        accessories.removeAllViews()
        fun add(v: View, w: Int = -2) = accessories.addView(v, lp(w).apply { bottomMargin = dp(8) })
        if (route == "home") {
            sendError?.let { add(text(it, 13f, c.error, 500).apply {
                setPadding(dp(12), dp(6), dp(12), dp(6)); background = rounded(c.card, 99)
            }.withMargins(dp(18))) }
            topic?.let { add(topicChip(it).withMargins(dp(18))) }
        }
        shownQuickReplies = showsQuickReplies
        if (shownQuickReplies == true) add(quickReplies(), -1)
        if (images.isNotEmpty()) add(attachmentStrip(), -1)
        field.hint = if (route == "home") SupportText.homeHint(topic?.prompt, persona?.name) else s("support.composer.placeholder.thread")
    }

    private fun View.withMargins(h: Int): View = FrameLayout(context).also { holder ->
        holder.clipChildren = false; holder.clipToPadding = false
        holder.addView(this, FrameLayout.LayoutParams(-2, -2).apply { marginStart = h; marginEnd = h })
    }

    private fun topicChip(t: AppReferSupportTopic): View {
        val chip = row().apply {
            setPaddingRelative(dp(12), dp(6), dp(6), dp(6)); background = rounded(c.card, 99, supportAlpha(t.tint, .25f))
        }
        chip.addView(icon(t.icon, t.tint, 14), lp(dp(14), dp(14)))
        chip.addView(text(t.title, 14f, t.tint, 600, 1), lp(-2, start = dp(6)))
        chip.addView(circleButton(R.drawable.apprefer_support_close, s("support.a11y.removeTopic"), 24, 12, t.tint, ColorDrawable(Color.TRANSPARENT)) {
            topic = null; renderAccessories(); saveDraft()
        }, lp(dp(24), dp(24), start = dp(2)))
        return chip
    }

    private fun quickReplies(): View {
        val strip = row().apply { setPadding(dp(14), 0, dp(14), 0) }
        fun chip(title: String, action: () -> Unit) = text(title, 14f, p.accent, 600, 1).apply {
            setPadding(dp(13), dp(8), dp(13), dp(8)); background = rounded(c.card, 99, supportAlpha(p.accent, .18f))
            pressable(title, action)
        }
        val thanks = s("support.thread.quickReply.thanks")
        strip.addView(chip(thanks) { sendText(thanks) })
        strip.addView(chip(s("support.thread.quickReply.another")) { focusField() }, lp(-2, start = dp(8)))
        return HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(strip) }
    }

    private fun attachmentStrip(): View {
        val strip = row().apply { gravity = Gravity.BOTTOM; setPadding(dp(18), dp(2), dp(18), dp(4)) }
        images.toList().forEachIndexed { index, bytes ->
            val holder = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
            val (frame, picture) = framedImage(62, 88, 12)
            picture.setImageBitmap(thumb(bytes)); picture.contentDescription = s("support.a11y.screenshotNumbered", "number" to "${index + 1}")
            holder.addView(frame, FrameLayout.LayoutParams(dp(62), dp(88), Gravity.BOTTOM or Gravity.START))
            val remove = circleButton(R.drawable.apprefer_support_close, s("support.a11y.removeScreenshotNumbered", "number" to "${index + 1}"), 22, 12, Color.WHITE, oval(0x99000000.toInt(), Color.WHITE)) {
                if (!starting && index < images.size) { images.removeAt(index); renderAccessories(); updateSend(); saveDraft() }
            }
            remove.elevation = dpf(4)
            holder.addView(remove, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
            strip.addView(holder, lp(dp(68), dp(94), start = if (index > 0) dp(4) else 0))
        }
        if (images.size < 4) strip.addView(FrameLayout(context).apply {
            background = rounded(supportAlpha(c.card, .9f), 12); addView(icon(R.drawable.apprefer_support_add, p.accent, 22))
            pressable(s("support.a11y.addAnotherScreenshot")) { pick() }
        }, lp(dp(62), dp(88), start = dp(10)))
        return HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; clipChildren = false; addView(strip) }
    }

    /** Small decoded copies for the strip and pending bubbles (the upload keeps the full JPEG). */
    private fun thumb(bytes: ByteArray): Bitmap? = thumbs[bytes] ?: run {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 240 && bounds.outHeight / (sample * 2) >= 240) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })?.also { thumbs[bytes] = it }
    }

    private fun viewImage(url: String) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = FrameLayout(context).apply {
            setBackgroundColor(Color.BLACK); layoutDirection = this@AppReferSupportView.layoutDirection
            contentDescription = s("support.a11y.closeScreenshot")
        }
        val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = s("support.a11y.screenshot") }
        root.addView(picture, FrameLayout.LayoutParams(-1, -1))
        val spinner = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }
        root.addView(spinner, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        root.addView(circleButton(R.drawable.apprefer_support_close, s("support.a11y.close"), 40, 18, Color.WHITE, oval(0x33FFFFFF)) { dialog.dismiss() },
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        root.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.show()
        scope.launch {
            try { picture.setImageBitmap(withContext(Dispatchers.IO) { SupportImages.remote(url) }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { picture.contentDescription = s("support.a11y.imageUnavailable") }
            spinner.visibility = View.GONE
        }
    }

    // MARK: - Actions

    private fun pick() { if (images.size < 4 && !starting) onPickImages?.invoke(4 - images.size) }

    private fun pick(t: AppReferSupportTopic) {
        t.action?.let { it(); return }
        topic = t; renderAccessories(); saveDraft(); focusField()
    }

    private fun focusField() {
        if (!::field.isInitialized) return
        field.requestFocus(); field.setSelection(field.text.length)
        field.post { (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT) }
    }

    private fun hideKeyboard() {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
    }

    private fun openThread(conversation: AppReferSupportConversation) {
        if (starting) return
        id = conversation.id; subject = conversation.subject; timeline.reset(); threadLoaded = false; loadError = null
        navigate("thread")
    }

    /** Starts a conversation from the home field; the message stays in place if it fails. */
    private fun startConversation() {
        if (starting) return
        val text = draft.trim()
        if (text.isEmpty() && images.isEmpty()) return
        starting = true; sendError = null
        updateSend(); renderAccessories(); saveDraft()
        val epoch = api.actionGeneration
        val sentDraft = draft; val shots = images.toList(); val about = topic?.title; val aboutId = topic?.id; val clientId = homeClientMessageId
        scope.launch {
            try {
                // Every attempt uses fresh upload slots but keeps the same send identity.
                val ids = shots.map { api.upload(it, epoch) }
                val t = api.start(text, ids, clientId, about, epoch)
                starting = false
                // Clear only what was sent: anything typed or picked meanwhile stays as the next home draft.
                if (draft == sentDraft) draft = ""
                images.removeAll { kept -> shots.any { it === kept } }
                if (topic?.id == aboutId) topic = null
                homeClientMessageId = UUID.randomUUID().toString()
                id = t.conversation.id; subject = t.conversation.subject
                timeline.reset(); timeline.merge(t.messages); threadLoaded = true; loadError = null
                hideKeyboard()
                navigate("thread")
            } catch (e: CancellationException) { starting = false; throw e }
            catch (e: Exception) {
                starting = false; sendError = SupportStrings.errorText(e, "support.error.notSent")
                if (route == "home") { updateSend(); renderAccessories() }
            } finally { if (isAttachedToWindow) saveDraft() }
        }
    }

    private fun sendTyped() {
        val text = draft.trim()
        if (text.isEmpty() && images.isEmpty()) return
        val shots = images.toList()
        images.clear(); field.setText("")
        send(text, shots)
    }

    private fun sendText(text: String) = send(text, emptyList())

    private fun send(text: String, shots: List<ByteArray>) {
        val conversation = id ?: return
        val message = SupportPending(conversation, text, shots)
        pending.add(message); renderAccessories(); updateSend(); deliver(message)
    }

    private fun deliver(message: SupportPending) {
        val epoch = api.actionGeneration
        val conversation = message.conversationId ?: return
        message.failed = false
        saveDraft(); renderMessages()
        scope.launch {
            try {
                val ids = message.images.map { api.upload(it, epoch) }
                val m = api.send(conversation, message.text, ids, message.clientMessageId, epoch)
                pending.remove(message)
                if (route == "thread" && id == conversation) { timeline.merge(listOf(m)); absorbOffers(listOf(m)) }
            } catch (e: CancellationException) { message.failed = true; throw e }
            catch (_: Exception) { message.failed = true }
            finally { if (isAttachedToWindow) { saveDraft(); renderMessages() } }
        }
    }

    private fun navigate(next: String) {
        if (route == "home" && next == "thread") {
            homeStash = HomeStash(draft, images.toList(), topic, sendError)
            draft = ""; images.clear(); topic = null; sendError = null
            hideKeyboard()
        } else if (route == "thread" && next == "home") {
            hideKeyboard()
            val stash = homeStash; homeStash = null
            draft = stash?.draft ?: ""; images.clear(); stash?.let { images.addAll(it.images) }; topic = stash?.topic; sendError = stash?.error
        } else if (route != next) {
            // Help screens have no field of their own: the home's draft stays where it is.
            hideKeyboard()
        }
        route = next; loadError = null; render(); saveDraft()
        if (visible) startPolling()
    }

    fun goBack(): Boolean {
        if (starting) return true
        when (route) {
            "home" -> return false
            "article" -> navigate(articleBack)
            else -> navigate("home")
        }
        return true
    }

    fun addImages(uris: List<Uri>) {
        scope.launch {
            try {
                val room = 4 - images.size
                val bytes = withContext(Dispatchers.IO) { uris.take(room).map { SupportImages.jpeg(context, it) } }
                images.addAll(bytes.take(4 - images.size)); sendError = null
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { sendError = s("support.error.imageUnreadable") }
            renderAccessories(); updateSend(); saveDraft()
        }
    }

    fun pause() { visible = false; polling?.cancel(); polling = null; SupportOffers.billingListeners.remove(billingListener) }
    fun resume() {
        visible = true; startPolling()
        // Offer cards follow purchases, restores and sign-ins as Billing reports them, and coming back.
        SupportOffers.billingListeners.add(billingListener)
        scope.launch { refreshOffers(); runCatching { api.flushOfferReceipts(offerQueue) } }
    }

    private fun startPolling() {
        polling?.cancel()
        polling = scope.launch {
            while (true) {
                val screen = route; val conversation = id
                try {
                    if (screen == "home") {
                        refreshArticles()
                        val list = api.conversations()
                        // Re-render only on change, so a poll never resets a press or the scroll.
                        if (route == "home" && (list != conversations || !homeLoaded || loadError != null)) {
                            conversations = list; homeLoaded = true; loadError = null; renderHomeContent()
                        }
                        if (route == "home") personaChanged()
                    } else if (screen == "thread" && conversation != null) {
                        val t = api.thread(conversation, cursor)
                        if (route == "thread" && id == conversation) {
                            subject = t.conversation.subject
                            val before = messages.size; val first = !threadLoaded || loadError != null
                            timeline.merge(t.messages, fromLoad = true); threadLoaded = true; loadError = null
                            absorbOffers(t.messages)
                            if (first || messages.size != before) renderMessages()
                            personaChanged()
                            if (first) runCatching { api.flushOfferReceipts(offerQueue) }
                            refreshOffers(force = false)
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    if (route == "home" || route == "thread") {
                        loadError = SupportStrings.errorText(e, if (route == "thread") "support.thread.loadFailed" else "support.error.loadFailed")
                        if (route == "home") { homeLoaded = true; renderHomeContent() } else if (messages.isEmpty()) renderMessages()
                    }
                }
                delay(if (route == "thread") 4_000 else 15_000)
            }
        }
    }

    // MARK: - Insets and system bars

    internal fun applyInsets(left: Int, top: Int, right: Int, bottom: Int) {
        insetL = left; insetT = top; insetR = right; insetB = bottom
        setPadding(left, 0, right, bottom)
        hero?.setPadding(dp(22), dp(18) + top, dp(22), dp(88))
        if (::scrim.isInitialized) scrim.layoutParams = (scrim.layoutParams as LayoutParams).apply { height = top }
        header?.let { it.layoutParams = (it.layoutParams as LayoutParams).apply { topMargin = dp(10) + top } }
        if (route == "thread" && ::body.isInitialized) body.setPadding(dp(14), dp(86) + top, dp(14), body.paddingBottom)
        if ((route == "help" || route == "article") && ::body.isInitialized) body.setPadding(dp(16), dp(86) + top, dp(16), dp(32))
        updateChrome()
    }

    /** Status bar: over the hero its icons follow the header's text colour; elsewhere a solid band in the page colour. */
    private fun updateChrome() {
        if (!::scrim.isInitialized) return
        val overHero = route == "home" && hero.let { it == null || scroll.scrollY < it.height - insetT - dp(8) }
        if (route == "home") scrim.alpha = if (overHero) 0f else 1f
        val darkIcons = if (overHero) h.foregroundIsDark else !dark
        if (darkIcons != lastStatusDark) { lastStatusDark = darkIcons; onStatusBarStyle?.invoke(darkIcons) }
    }

    /** Hides the keyboard when the customer drags the page, and keeps a conversation pinned to its end. */
    private inner class SupportScrollView(context: Context) : ScrollView(context) {
        private var downY = 0; private var dismissed = false; private var pinPending = false; private var animate = false
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        init { isVerticalScrollBarEnabled = false; clipToPadding = false; isFillViewport = true }
        override fun onScrollChanged(l: Int, t: Int, oldl: Int, oldt: Int) { super.onScrollChanged(l, t, oldl, oldt); updateChrome() }
        override fun dispatchTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downY = scrollY; dismissed = false }
                MotionEvent.ACTION_MOVE -> if (!dismissed && abs(scrollY - downY) > slop) { dismissed = true; hideKeyboard() }
            }
            return super.dispatchTouchEvent(e)
        }
        fun scrollToBottom(animated: Boolean) { pinPending = true; animate = animated; requestLayout() }
        fun stickToBottom() { if (atBottom()) scrollToBottom(false) }
        private fun atBottom() = getChildAt(0)?.let { scrollY + height >= it.height - dp(24) } ?: true
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val wasAtBottom = route == "thread" && oldh > 0 && getChildAt(0)?.let { scrollY + oldh >= it.height - dp(24) } == true
            super.onSizeChanged(w, h, oldw, oldh)
            if (wasAtBottom && h < oldh) { pinPending = true; animate = false }
        }
        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            super.onLayout(changed, l, t, r, b)
            if (!pinPending) return
            pinPending = false
            val end = max(0, (getChildAt(0)?.height ?: 0) - height)
            if (animate) smoothScrollTo(0, end) else scrollTo(0, end)
        }
    }

    // MARK: - Saved state

    // Large images stay out of Binder saved-state parcels during Activity recreation.
    internal fun saveDraft(): String? = runCatching {
        if (accountReset) return@runCatching null
        val file = draftFile ?: java.io.File.createTempFile("apprefer-support-", ".draft", context.cacheDir).also { draftFile = it }
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val out = java.io.DataOutputStream(stream)
            // The first field stays "home" or "thread" (what older saves hold); help screens are added at the end.
            out.writeUTF(if (route == "thread") "thread" else "home"); out.writeUTF(id ?: ""); out.writeUTF(subject)
            val text = draft.toByteArray(Charsets.UTF_8); out.writeInt(text.size); out.write(text)
            out.writeInt(images.size); images.forEach { out.writeInt(it.size); out.write(it) }
            out.writeInt(pending.size)
            pending.forEach { it.write(out) }
            out.writeUTF(topic?.id ?: ""); out.writeUTF(homeClientMessageId)
            val stash = homeStash
            out.writeBoolean(stash != null)
            if (stash != null) {
                val stashText = stash.draft.toByteArray(Charsets.UTF_8); out.writeInt(stashText.size); out.write(stashText)
                out.writeInt(stash.images.size); stash.images.forEach { out.writeInt(it.size); out.write(it) }
                out.writeUTF(stash.topic?.id ?: "")
            }
            // 0.10.0: the help screen on show, so a rotation keeps the article or the help centre.
            out.writeUTF(if (route == "help" || route == "article") route else "")
            out.writeUTF(articleSlug ?: ""); out.writeUTF(articleTitle.take(500)); out.writeUTF(articleBack); out.writeUTF(helpQuery.take(200))
            out.flush(); atomic.finishWrite(stream)
        } catch (e: Exception) { atomic.failWrite(stream); throw e }
        file.name
    }.getOrNull()

    internal fun restoreDraft(filename: String) {
        if (!filename.startsWith("apprefer-support-") || filename.contains('/')) return
        runCatching {
            val file = java.io.File(context.cacheDir, filename)
            if (file.length() > 64_000_000) return
            java.io.DataInputStream(file.inputStream().buffered()).use { input ->
                val savedRoute = input.readUTF(); require(savedRoute in listOf("home", "compose", "thread"))
                val savedId = input.readUTF().takeIf { it.isNotEmpty() }; val savedSubject = input.readUTF()
                // Older saves had a separate compose screen; it is now the home field.
                route = if (savedRoute == "thread" && savedId != null) "thread" else "home"
                id = savedId; subject = savedSubject
                val size = input.readInt(); require(size in 0..1_000_000)
                draft = String(ByteArray(size).also { input.readFully(it) }, Charsets.UTF_8)
                images.clear(); val count = input.readInt(); require(count in 0..4)
                repeat(count) { val length = input.readInt(); require(length in 1..700_000); images.add(ByteArray(length).also { input.readFully(it) }) }
                pending.clear()
                if (input.available() > 0) {
                    val countPending = input.readInt(); require(countPending in 0..100)
                    repeat(countPending) {
                        val restored = SupportPending.read(input)
                        // A new conversation that was being sent goes back into the home field, same identity.
                        if (restored.conversationId == null) {
                            if (draft.isEmpty()) draft = restored.text
                            if (images.isEmpty()) images.addAll(restored.images)
                            homeClientMessageId = restored.clientMessageId
                        } else pending.add(restored)
                    }
                }
                if (input.available() > 0) {
                    val savedTopic = input.readUTF()
                    topic = savedTopic(savedTopic)
                    homeClientMessageId = input.readUTF().takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,64}")) } ?: homeClientMessageId
                }
                homeStash = null
                if (input.available() > 0 && input.readBoolean()) {
                    val stashSize = input.readInt(); require(stashSize in 0..1_000_000)
                    val stashText = String(ByteArray(stashSize).also { input.readFully(it) }, Charsets.UTF_8)
                    val stashCount = input.readInt(); require(stashCount in 0..4)
                    val stashImages = List(stashCount) { val length = input.readInt(); require(length in 1..700_000); ByteArray(length).also { input.readFully(it) } }
                    val stashTopic = savedTopic(input.readUTF())
                    if (route == "thread") homeStash = HomeStash(stashText, stashImages, stashTopic, null)
                }
                if (input.available() > 0) {
                    val screen = input.readUTF(); val slug = input.readUTF(); val title = input.readUTF(); val back = input.readUTF()
                    helpQuery = input.readUTF().take(200)
                    if (screen == "help") route = "help"
                    else if (screen == "article" && slug.isNotEmpty()) {
                        route = "article"; articleSlug = slug; articleTitle = title; articleBack = if (back == "help") "help" else "home"
                    }
                }
            }
            draftFile = file; render()
        }
    }

    /** A saved topic by id (older saves stored the title). */
    private fun savedTopic(saved: String): AppReferSupportTopic? = saved.takeIf { it.isNotEmpty() }?.let { value ->
        AppReferSupport.topics.firstOrNull { it.action == null && it.id == value } ?: AppReferSupport.topics.firstOrNull { it.action == null && it.title == value }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        accountLifecycle.attach()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        resume()
        if (focusOnOpen && route == "home") { focusOnOpen = false; focusField() }
    }
    override fun onDetachedFromWindow() { accountLifecycle.detach(); pause(); pending.forEach { it.failed = true }; saveDraft(); scope.cancel(); super.onDetachedFromWindow() }

    // MARK: - Dates

    private fun date(iso: String): Long {
        val normalized = iso.replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        return listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(normalized)?.time }.getOrNull()
        } ?: 0L
    }
    /** A clock time in the chat's language, following the device's 12/24-hour setting. */
    private fun clockFormat(): java.text.DateFormat {
        val locale = SupportStrings.locale
        val skeleton = if (android.text.format.DateFormat.is24HourFormat(context)) "Hm" else "hm"
        return SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    }
    private fun clock(iso: String): String = date(iso).takeIf { it > 0 }?.let { clockFormat().format(Date(it)) } ?: ""
    /** "Today, 12:35", "Yesterday, 09:10" or "14 Oct 2026, 18:02", in the chat's language. */
    private fun time(iso: String): String {
        val ms = date(iso).takeIf { it > 0 } ?: return ""
        val clock = clockFormat().format(Date(ms))
        val day = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance()
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            same(day, today) -> s("support.time.today", "time" to clock)
            same(day, yesterday) -> s("support.time.yesterday", "time" to clock)
            else -> "${java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, SupportStrings.locale).format(Date(ms))}, $clock"
        }
    }
}

/** A round avatar: a person's initial, or an icon, on a diagonal gradient, optionally ringed in white. */
internal class SupportAvatarView(
    context: Context, private val colors: IntArray, private val initial: String?, icon: Int?, private val ring: Boolean,
    ringColor: Int = 0xE6FFFFFF.toInt(),
) : View(context) {
    private val glyph = icon?.let { context.getDrawable(it)?.mutate()?.apply { setTint(Color.WHITE) } }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ringColor }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER
        typeface = if (Build.VERSION.SDK_INT >= 28) Typeface.create(Typeface.DEFAULT, 700, false) else Typeface.DEFAULT_BOLD
    }
    private val photo = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    /** A portrait (the AI assistant's); the initial shows until it loads, or if it can't. */
    var image: Bitmap? = null
        set(value) { field = value; invalidate() }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val r = w / 2
        fill.shader = LinearGradient(0f, 0f, w, w, colors[0], colors[1], Shader.TileMode.CLAMP)
        canvas.drawCircle(r, r, r, fill)
        val picture = image
        if (picture != null && picture.width > 0 && picture.height > 0) {
            // Centre-crop the portrait into the circle.
            val scale = max(w / picture.width, w / picture.height)
            photo.shader = BitmapShader(picture, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply { setScale(scale, scale); postTranslate((w - picture.width * scale) / 2, (w - picture.height * scale) / 2) })
            }
            canvas.drawCircle(r, r, r, photo)
        }
        if (ring) { val sw = resources.displayMetrics.density * 2; stroke.strokeWidth = sw; canvas.drawCircle(r, r, r - sw / 2, stroke) }
        if (picture != null) return
        if (glyph != null) {
            val s = (w * 0.44f).toInt(); val o = ((w - s) / 2).toInt()
            glyph.setBounds(o, o, o + s, o + s); glyph.draw(canvas)
        } else {
            label.textSize = w * 0.4f
            canvas.drawText(initial ?: "•", r, r - (label.descent() + label.ascent()) / 2, label)
        }
    }
}

/** The home hero: the accent with three soft radial glows (light, hot and warm neighbours), or the plain header's flat grey. */
internal class SupportHeroDrawable(private val p: SupportPalette, private val h: SupportHeaderLook, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.shader = null; paint.color = h.background; canvas.drawRect(b, paint)
        if (!h.glows) return
        glow(canvas, p.light, 1f, .10f, 0f, 380f)
        glow(canvas, p.hot, 1f, 1f, .2f, 320f)
        glow(canvas, p.warm, .95f, .6f, 1.05f, 340f)
    }
    private fun glow(canvas: Canvas, color: Int, alpha: Float, x: Float, y: Float, radius: Float) {
        val b = bounds
        paint.shader = RadialGradient(b.left + b.width() * x, b.top + b.height() * y, radius * density,
            intArrayOf(supportAlpha(color, alpha), supportAlpha(color, 0f)), null, Shader.TileMode.CLAMP)
        canvas.drawRect(b, paint)
    }
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Drawable API") override fun getOpacity() = PixelFormat.OPAQUE
}

/** Lays children out in rows that wrap, so screenshots never run past a narrow screen. */
internal class SupportFlowRow(context: Context, private val gap: Int, private val alignEnd: Boolean) : ViewGroup(context) {
    init { clipChildren = false; clipToPadding = false }
    private val lines = mutableListOf<List<View>>()
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else MeasureSpec.getSize(widthMeasureSpec)
        lines.clear()
        var line = mutableListOf<View>(); var lineWidth = 0; var widest = 0; var height = 0; var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == GONE) continue
            val lp = child.layoutParams
            // A child never measures wider than the row allows.
            val w = minOf(lp.width.coerceAtLeast(0), limit)
            val h = if (lp.width > 0 && w < lp.width) lp.height * w / lp.width else lp.height
            child.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
            val add = if (line.isEmpty()) w else gap + w
            if (line.isNotEmpty() && lineWidth + add > limit) {
                lines.add(line); widest = maxOf(widest, lineWidth); height += lineHeight + gap
                line = mutableListOf(); lineWidth = 0; lineHeight = 0
            }
            lineWidth += if (line.isEmpty()) w else gap + w
            lineHeight = maxOf(lineHeight, h); line.add(child)
        }
        if (line.isNotEmpty()) { lines.add(line); widest = maxOf(widest, lineWidth); height += lineHeight }
        setMeasuredDimension(resolveSize(widest, widthMeasureSpec), resolveSize(height, heightMeasureSpec))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        // Children run from the start edge; in a right-to-left layout that is the right.
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var y = 0
        lines.forEach { line ->
            val lineWidth = line.sumOf { it.measuredWidth } + gap * (line.size - 1)
            val atRight = alignEnd != rtl
            var x = if (atRight) width - lineWidth else 0
            (if (rtl) line.asReversed() else line).forEach { child -> child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight); x += child.measuredWidth + gap }
            y += line.maxOf { it.measuredHeight } + gap
        }
    }
}
