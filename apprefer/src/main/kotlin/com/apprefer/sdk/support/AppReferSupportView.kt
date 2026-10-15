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
    private val night get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    @Suppress("DEPRECATION") // Bar colours and decor fitting are still needed below API 35.
    override fun onCreate(state: Bundle?) {
        setTheme(if (night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        chat = AppReferSupportView(this, intent.getStringExtra("conversationId"), intent.getStringExtra("draft"))
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

/**
 * Native embeddable help chat: the help home (with its message field) and conversations.
 * Call pause/resume with the host lifecycle.
 */
class AppReferSupportView @JvmOverloads constructor(
    context: Context, conversationId: String? = null, composing: String? = null,
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
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var visible = false

    private var route = if (conversationId != null) "thread" else "home"
    private var id = conversationId
    private var subject = "Conversation"
    private val timeline = SupportTimeline { date(it) }
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
    private val failedPortraits = mutableSetOf<String>()
    private var shownQuickReplies: Boolean? = null
    private var renderedCount = -1
    private var lastStatusDark: Boolean? = null
    private val thumbs = WeakHashMap<ByteArray, Bitmap>()

    init { setBackgroundColor(backgroundColor); render() }

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
        if (lines > 0) { maxLines = lines; ellipsize = TextUtils.TruncateAt.END }
    }
    private fun label(value: String) = text(value.uppercase(Locale.getDefault()), 12f, c.muted, 700).apply { letterSpacing = 0.05f }
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
    /** Per-corner radii in dp: top-left, top-right, bottom-right, bottom-left. */
    private fun corners(tl: Number, tr: Number, br: Number, bl: Number) =
        floatArrayOf(dpf(tl), dpf(tl), dpf(tr), dpf(tr), dpf(br), dpf(br), dpf(bl), dpf(bl))
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
    private fun avatar(name: String?, size: Number, colors: IntArray, icon: Int? = null, ring: Boolean = false, imageUrl: String? = null) =
        SupportAvatarView(context, colors, name?.let { SupportText.initial(it) }, icon, ring).apply {
            layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
            imageUrl?.let { url -> portrait(url) { image = it } }
        }
    private val agentColors get() = intArrayOf(p.warm, p.hot)
    /** Uses the shared image cache; a portrait that can't load isn't tried again by this view. */
    private fun portrait(url: String, show: (Bitmap) -> Unit) {
        SupportImages.cached(url)?.let { show(it); return }
        if (url in failedPortraits) return
        scope.launch {
            try { show(withContext(Dispatchers.IO) { SupportImages.remote(url) }) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { failedPortraits.add(url) }
        }
    }
    /** The persona as last read from AppRefer (shared by every help screen). */
    private val persona get() = api.persona
    /** The small "AI" tag on everything the AI assistant says. */
    private fun aiTag() = text("AI", 10f, p.accent, 900).apply {
        letterSpacing = 0.04f; setPadding(dp(6), dp(2), dp(6), dp(2))
        background = rounded(supportAlpha(p.accent, .12f), 6); contentDescription = "AI assistant"
    }
    private fun badge(count: Int) = text("$count", 12f, p.onAccent, 700).apply {
        background = rounded(p.accent, 99); setPadding(dp(8), dp(3), dp(8), dp(3)); contentDescription = "$count unread"
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
        removeAllViews(); hero = null; header = null; headerLabels = null; headerAvatar = null; headerKey = null; presenceHolder = null; shownQuickReplies = null; renderedCount = -1; lastStatusDark = null
        shownPersona = persona
        if (route == "home") buildHome() else buildThread()
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
        val box = column().apply { background = SupportHeroDrawable(p, resources.displayMetrics.density) }
        val top = row().apply { gravity = Gravity.TOP }
        top.addView(appIconView())
        top.addView(Space(context), lp(0, 1, weight = 1f))
        top.addView(circleButton(R.drawable.apprefer_support_close, "Close", 40, 18, p.onAccent,
            oval(supportAlpha(p.onAccent, .2f), supportAlpha(p.onAccent, .35f))) { if (!goBack()) onClose() })
        box.addView(top, lp())
        box.addView(text(title(), 15f, supportAlpha(p.onAccent, .85f), 600, 1), lp(top = dp(20)))
        box.addView(text(SupportText.greeting(AppReferSupport.name), 38f, p.onAccent, 800).apply {
            letterSpacing = -0.026f; maxLines = 1
            if (Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(22, 38, 1, TypedValue.COMPLEX_UNIT_SP) else ellipsize = TextUtils.TruncateAt.END
        }, lp(top = dp(4)))
        box.addView(text("How can we help today?", 19f, supportAlpha(p.onAccent, .92f), 500), lp(top = dp(6)))
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
        } else text(SupportText.initials(appName()), 15f, p.light, 800).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF29205C.toInt(), 0xFF0F0A1F.toInt())).apply { cornerRadius = dpf(13) }
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
            setPadding(dp(8), dp(7), dp(14), dp(7))
            background = rounded(supportAlpha(Color.BLACK, if (p.onAccentIsDark) .06f else .16f), 22, supportAlpha(p.onAccent, .3f))
        }
        // The AI assistant first (with its portrait), then the team.
        val faces = mutableListOf<View>()
        persona?.let { faces.add(avatar(it.name, 28, agentColors, ring = true, imageUrl = it.avatarUrl)) }
        names.forEachIndexed { i, n -> faces.add(avatar(n, 28, avatarColors(i), ring = true)) }
        if (faces.isEmpty()) faces.add(avatar(null, 28, agentColors, R.drawable.apprefer_support_bubble, ring = true))
        val stack = FrameLayout(context)
        val count = faces.size
        // Added last-first so the first face sits on top, like the iOS pill.
        for (i in count - 1 downTo 0) stack.addView(faces[i], FrameLayout.LayoutParams(dp(28), dp(28)).apply { leftMargin = dp(20) * i })
        pill.addView(stack, lp(dp(28) + dp(20) * (count - 1), dp(28)))
        val line = SupportText.presence(AppReferSupport.teamNames, AppReferSupport.replyTime, persona?.name)
        pill.addView(text(line, 14f, p.onAccent, 500).apply { maxLines = 2 }, lp(-2, start = dp(10)))
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
        if (AppReferSupport.topics.isNotEmpty()) add(quickHelp(sections.current != null))
        if (sections.others.isNotEmpty()) add(pastConversations(sections.others))
        if (!homeLoaded && conversations.isEmpty()) add(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(p.accent) }.let {
            FrameLayout(context).apply { addView(it, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER)) }
        })
        loadError?.takeIf { conversations.isEmpty() && homeLoaded }?.let { add(text(it, 13f, c.muted).apply { setPadding(dp(6), 0, dp(6), 0) }) }
    }

    private fun continueCard(conversation: AppReferSupportConversation): View {
        val card = column().apply { setPadding(dp(16), dp(16), dp(16), dp(16)); background = rounded(c.card, 26) }
        shadow(card, 12, .22f)
        card.addView(label("Continue"))
        val line = row()
        val agent = SupportText.firstName(AppReferSupport.teamNames.firstOrNull())
        val persona = persona
        line.addView(if (conversation.lastFrom == "bot" && persona != null) avatar(persona.name, 46, agentColors, imageUrl = persona.avatarUrl)
            else avatar(agent, 46, agentColors, if (agent == null) R.drawable.apprefer_support_forum else null))
        val labels = column()
        labels.addView(text(conversation.subject, 16f, c.ink, 600, 1))
        labels.addView(text(if (conversation.lastFrom == "customer") "You: ${conversation.preview}" else conversation.preview, 14f, c.muted, lines = 1), lp(top = dp(3)))
        line.addView(labels, lp(0, weight = 1f, start = dp(12)))
        line.addView(if (conversation.unread > 0) badge(conversation.unread) else text(time(conversation.lastMessageAt), 12f, c.muted), lp(-2, start = dp(6)))
        card.addView(line, lp(top = dp(12)))
        card.pressable("Continue: ${conversation.subject}") { openThread(conversation) }
        return card
    }

    private fun quickHelp(showLabel: Boolean): View {
        val box = column()
        if (showLabel) box.addView(label("Quick help").apply { setPadding(dp(6), 0, 0, 0) }, lp())
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
        box.addView(label("Your conversations").apply { setPadding(dp(6), 0, 0, 0) })
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
        line.contentDescription = conversation.subject
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
        composerBox = buildComposer("Message", R.drawable.apprefer_support_add) { sendTyped() }
        addView(composerBox, LayoutParams(-1, -2, Gravity.BOTTOM))
        composerBox.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) post {
                if (route == "thread") { body.setPadding(dp(14), dp(86) + insetT, dp(14), composerBox.height + dp(12)); scroll.stickToBottom() }
            }
        }
        renderMessages(); renderAccessories(); updateSend()
    }

    private fun buildHeader(): View {
        val bar = row().apply { setPadding(dp(10), 0, dp(10), 0); background = rounded(c.card, 32) }
        shadow(bar, 8, .14f)
        bar.addView(circleButton(R.drawable.apprefer_support_back, "Back", 42, 18, p.accent, oval(supportAlpha(c.ink, .05f))) { goBack() })
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
                addView(text(persona.name, 16f, c.ink, 700, 1), lp(-2))
                addView(aiTag(), lp(-2, start = dp(6)))
            }, lp(-2))
            labels.addView(text("${appName()} team · replies instantly", 12.5f, c.muted, lines = 1), lp(top = dp(2)))
            avatar(persona.name, 42, agentColors, imageUrl = persona.avatarUrl)
        } else {
            labels.addView(text(agent ?: "${appName()} team", 16f, c.ink, 700, 1))
            labels.addView(text("Usually replies in ${AppReferSupport.replyTime}", 12.5f, c.muted, lines = 1), lp(top = dp(2)))
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
            if (loadError != null) body.addView(text(loadError ?: "", 14f, c.muted).apply { gravity = Gravity.CENTER }, lp(top = dp(30)))
            else body.addView(FrameLayout(context).apply {
                addView(ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(p.accent) }, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
            }, lp(top = dp(30)))
        }
        messages.forEachIndexed { i, m ->
            val timed = showsTime(i)
            if (timed) body.addView(text(time(m.createdAt), 12f, c.muted, 600).apply { gravity = Gravity.CENTER }, lp().apply {
                topMargin = if (i == 0) dp(4) else dp(16); bottomMargin = dp(8)
            })
            val last = i == messages.size - 1 || !sameSide(i, i + 1) || showsTime(i + 1)
            val view = when {
                m.from == "customer" -> customerBubble(m, last)
                m.from == "bot" && SupportText.firstName(m.authorName) != null -> personaMessage(m, requireNotNull(SupportText.firstName(m.authorName)), last)
                m.from == "bot" -> botCard(m)
                else -> teamBubble(m, last)
            }
            body.addView(view, lp(top = if (i > 0 && !timed) (if (sameSide(i - 1, i)) dp(3) else dp(10)) else 0))
        }
        waiting.forEach { body.addView(pendingBubble(it), lp(top = dp(6))) }
        val last = messages.lastOrNull()
        if (threadLoaded && waiting.isEmpty() && last?.from == "customer") {
            body.addView(text("Sent ${clock(last.createdAt)} · we'll notify you when the team replies", 11.5f, c.muted, 500).apply {
                gravity = Gravity.END; setPadding(0, 0, dp(4), 0)
            }, lp(top = dp(5)))
        }
        updateHeader()
        val count = messages.size + waiting.size
        if (count != renderedCount) { scroll.scrollToBottom(animated = renderedCount > 0); renderedCount = count }
        refreshQuickReplies()
    }

    private fun bubbleText(value: String, color: Int) = text(value, 16f, color).apply {
        setLineSpacing(dpf(2), 1f); setPadding(dp(15), dp(11), dp(15), dp(11)); setTextIsSelectable(true)
    }

    private fun thumbnails(urls: List<AppReferSupportAttachment>, end: Boolean): View {
        val strip = SupportFlowRow(context, dp(6), end)
        urls.forEach { a ->
            val (frame, picture) = framedImage(74, 112, 14)
            picture.contentDescription = a.name ?: "Screenshot"
            a.url?.let { url ->
                frame.pressable(a.name ?: "Screenshot") { viewImage(url) }
                val cached = SupportImages.cached(url)
                if (cached != null) picture.setImageBitmap(cached)
                else scope.launch { try { picture.setImageBitmap(withContext(Dispatchers.IO) { SupportImages.remote(url) }) } catch (_: Exception) {} }
            }
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
        val box = column().apply { gravity = Gravity.END; setPadding(dp(60), 0, 0, 0) }
        val shots = m.attachments.filter { it.contentType?.startsWith("image/") == true }
        if (shots.isNotEmpty()) box.addView(thumbnails(shots, true), lp(-2))
        if (m.text.isNotEmpty()) box.addView(bubbleText(m.text, p.onAccent).apply {
            background = gradient(corners(22, 22, if (tail) 6 else 22, 22))
        }, lp(-2, top = if (shots.isNotEmpty()) dp(6) else 0))
        return box
    }

    private fun teamBubble(m: AppReferSupportMessage, showAvatar: Boolean): View {
        val line = row().apply { gravity = Gravity.BOTTOM; setPadding(0, 0, dp(40), 0) }
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
        val line = row().apply { setPadding(0, 0, dp(50), 0) }
        val card = column().apply { setPadding(dp(14), dp(12), dp(14), dp(12)); background = rounded(c.card, 20) }
        shadow(card, 2, .06f)
        card.addView(text("✦ INSTANT ANSWER", 11.5f, p.accent, 700).apply { letterSpacing = 0.035f })
        card.addView(text(m.text, 15.5f, c.ink).apply { setLineSpacing(dpf(2), 1f); setTextIsSelectable(true) }, lp(-2, top = dp(4)))
        line.addView(card, lp(-2))
        return line
    }

    /** A message from the AI assistant: its portrait, name and "AI" tag, then the text; a hand-over adds who has it now. */
    private fun personaMessage(m: AppReferSupportMessage, name: String, last: Boolean): View {
        val persona = persona
        val showAvatar = last || m.isHandoff
        val line = row().apply { gravity = Gravity.BOTTOM; setPadding(0, 0, dp(40), 0) }
        if (showAvatar) line.addView(avatar(name, 30, agentColors, imageUrl = persona?.takeIf { it.name == name }?.avatarUrl))
        else line.addView(Space(context), lp(dp(30), dp(30)))
        val bubble = column().apply {
            setPadding(dp(15), dp(10), dp(15), dp(12))
            background = GradientDrawable().apply { setColor(c.card); cornerRadii = corners(22, 22, 22, if (showAvatar) 6 else 22) }
        }
        shadow(bubble, 2, .06f)
        bubble.addView(row().apply {
            addView(text(name, 13f, c.ink, 700, 1), lp(-2))
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
            setPadding(dp(5), dp(5), dp(12), dp(5)); background = rounded(c.card, 99, supportAlpha(c.muted, .18f))
        }
        pill.addView(avatar(to, 22, agentColors, if (to == null) R.drawable.apprefer_support_people else null))
        pill.addView(text(SupportText.handoff(to, AppReferSupport.replyTime), 12.5f, c.muted, 600, 2), lp(-2, start = dp(8)))
        return FrameLayout(context).apply { addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER_HORIZONTAL)) }
    }

    private fun pendingBubble(pend: SupportPending): View {
        val box = column().apply { gravity = Gravity.END; setPadding(dp(60), 0, 0, 0) }
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
        box.addView(if (pend.failed) text("Not sent · Tap to retry", 12f, c.error, 600).apply {
            setPadding(0, dp(4), 0, dp(4)); setOnClickListener { deliver(pend) }
        } else text("Sending…", 11.5f, c.muted, 500), lp(-2, top = dp(5)))
        return box
    }

    // MARK: - Composer

    private fun buildComposer(placeholder: String, leading: Int, onSend: () -> Unit): LinearLayout {
        val box = column().apply { setPadding(0, 0, 0, dp(8)) }
        accessories = column()
        box.addView(accessories, lp())
        val bar = row().apply {
            gravity = Gravity.BOTTOM; setPadding(dp(6), dp(7), dp(7), dp(7)); background = rounded(c.card, 29)
        }
        shadow(bar, 10, .18f)
        bar.addView(circleButton(leading, "Add screenshot", 44, 22, c.muted, ColorDrawable(Color.TRANSPARENT)) { pick() })
        field = EditText(context).apply {
            setText(draft); hint = placeholder; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); setTextColor(c.ink); setHintTextColor(supportAlpha(c.muted, .75f))
            background = null; minLines = 1; maxLines = 5; gravity = Gravity.CENTER_VERTICAL or Gravity.START
            setPadding(dp(4), dp(11), dp(4), dp(11)); minHeight = dp(44)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            highlightColor = supportAlpha(p.accent, .3f)
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
            addView(sendIcon); addView(sendSpinner); contentDescription = "Send"
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
        field.hint = if (route == "home") SupportText.homeHint(topic?.prompt, persona?.name) else "Message"
    }

    private fun View.withMargins(h: Int): View = FrameLayout(context).also { holder ->
        holder.clipChildren = false; holder.clipToPadding = false
        holder.addView(this, FrameLayout.LayoutParams(-2, -2).apply { marginStart = h; marginEnd = h })
    }

    private fun topicChip(t: AppReferSupportTopic): View {
        val chip = row().apply {
            setPadding(dp(12), dp(6), dp(6), dp(6)); background = rounded(c.card, 99, supportAlpha(t.tint, .25f))
        }
        chip.addView(icon(t.icon, t.tint, 14), lp(dp(14), dp(14)))
        chip.addView(text(t.title, 14f, t.tint, 600, 1), lp(-2, start = dp(6)))
        chip.addView(circleButton(R.drawable.apprefer_support_close, "Remove topic", 24, 12, t.tint, ColorDrawable(Color.TRANSPARENT)) {
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
        strip.addView(chip("👍 Thanks, that's sorted") { sendText("👍 Thanks, that's sorted") })
        strip.addView(chip("I have another question") { focusField() }, lp(-2, start = dp(8)))
        return HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; clipToPadding = false; addView(strip) }
    }

    private fun attachmentStrip(): View {
        val strip = row().apply { gravity = Gravity.BOTTOM; setPadding(dp(18), dp(2), dp(18), dp(4)) }
        images.toList().forEachIndexed { index, bytes ->
            val holder = FrameLayout(context).apply { clipChildren = false; clipToPadding = false }
            val (frame, picture) = framedImage(62, 88, 12)
            picture.setImageBitmap(thumb(bytes)); picture.contentDescription = "Screenshot ${index + 1}"
            holder.addView(frame, FrameLayout.LayoutParams(dp(62), dp(88), Gravity.BOTTOM or Gravity.START))
            val remove = circleButton(R.drawable.apprefer_support_close, "Remove screenshot", 22, 12, Color.WHITE, oval(0x99000000.toInt(), Color.WHITE)) {
                if (!starting && index < images.size) { images.removeAt(index); renderAccessories(); updateSend(); saveDraft() }
            }
            remove.elevation = dpf(4)
            holder.addView(remove, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP or Gravity.END))
            strip.addView(holder, lp(dp(68), dp(94), start = if (index > 0) dp(4) else 0))
        }
        if (images.size < 4) strip.addView(FrameLayout(context).apply {
            background = rounded(supportAlpha(c.card, .9f), 12); addView(icon(R.drawable.apprefer_support_add, p.accent, 22))
            pressable("Add another screenshot") { pick() }
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
        val root = FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }
        val picture = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; contentDescription = "Screenshot" }
        root.addView(picture, FrameLayout.LayoutParams(-1, -1))
        val spinner = ProgressBar(context).apply { indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }
        root.addView(spinner, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.CENTER))
        root.addView(circleButton(R.drawable.apprefer_support_close, "Close", 40, 18, Color.WHITE, oval(0x33FFFFFF)) { dialog.dismiss() },
            FrameLayout.LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.END).apply { setMargins(dp(16), dp(16), dp(16), dp(16)) })
        root.setOnClickListener { dialog.dismiss() }
        dialog.setContentView(root)
        dialog.show()
        scope.launch {
            try { picture.setImageBitmap(withContext(Dispatchers.IO) { SupportImages.remote(url) }) } catch (_: Exception) {}
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
        val sentDraft = draft; val shots = images.toList(); val about = topic?.title; val clientId = homeClientMessageId
        scope.launch {
            try {
                // Every attempt uses fresh upload slots but keeps the same send identity.
                val ids = shots.map { api.upload(it) }
                val t = api.start(text, ids, clientId, about)
                starting = false
                // Clear only what was sent: anything typed or picked meanwhile stays as the next home draft.
                if (draft == sentDraft) draft = ""
                images.removeAll { kept -> shots.any { it === kept } }
                if (topic?.title == about) topic = null
                homeClientMessageId = UUID.randomUUID().toString()
                id = t.conversation.id; subject = t.conversation.subject
                timeline.reset(); timeline.merge(t.messages); threadLoaded = true; loadError = null
                hideKeyboard()
                navigate("thread")
            } catch (e: CancellationException) { starting = false; throw e }
            catch (e: Exception) {
                starting = false; sendError = e.message ?: "Your message wasn't sent. Try again."
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
        val conversation = message.conversationId ?: return
        message.failed = false
        saveDraft(); renderMessages()
        scope.launch {
            try {
                val ids = message.images.map { api.upload(it) }
                val m = api.send(conversation, message.text, ids, message.clientMessageId)
                pending.remove(message)
                if (route == "thread" && id == conversation) timeline.merge(listOf(m))
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
        }
        route = next; loadError = null; render(); saveDraft()
        if (visible) startPolling()
    }

    fun goBack(): Boolean {
        if (starting) return true
        if (route == "home") return false
        navigate("home"); return true
    }

    fun addImages(uris: List<Uri>) {
        scope.launch {
            try {
                val room = 4 - images.size
                val bytes = withContext(Dispatchers.IO) { uris.take(room).map { SupportImages.jpeg(context, it) } }
                images.addAll(bytes.take(4 - images.size)); sendError = null
            } catch (e: Exception) { sendError = e.message ?: "This image could not be read" }
            renderAccessories(); updateSend(); saveDraft()
        }
    }

    fun pause() { visible = false; polling?.cancel(); polling = null }
    fun resume() { visible = true; startPolling() }

    private fun startPolling() {
        polling?.cancel()
        polling = scope.launch {
            while (true) {
                val screen = route; val conversation = id
                try {
                    if (screen == "home") {
                        val list = api.conversations()
                        // Re-render only on change, so a poll never resets a press or the scroll.
                        if (route == "home" && (list != conversations || !homeLoaded || loadError != null)) {
                            conversations = list; homeLoaded = true; loadError = null; renderHomeContent()
                        }
                        if (route == "home") personaChanged()
                    } else if (conversation != null) {
                        val t = api.thread(conversation, cursor)
                        if (route == "thread" && id == conversation) {
                            subject = t.conversation.subject
                            val before = messages.size; val first = !threadLoaded || loadError != null
                            timeline.merge(t.messages, fromLoad = true); threadLoaded = true; loadError = null
                            if (first || messages.size != before) renderMessages()
                            personaChanged()
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    loadError = e.message ?: "Couldn't load. Check your connection."
                    if (route == "home") { homeLoaded = true; renderHomeContent() } else if (messages.isEmpty()) renderMessages()
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
        updateChrome()
    }

    /** Status bar: over the hero its icons follow the accent; elsewhere a solid band in the page colour. */
    private fun updateChrome() {
        if (!::scrim.isInitialized) return
        val overHero = route == "home" && hero.let { it == null || scroll.scrollY < it.height - insetT - dp(8) }
        if (route == "home") scrim.alpha = if (overHero) 0f else 1f
        val darkIcons = if (overHero) p.onAccentIsDark else !dark
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
        val file = draftFile ?: java.io.File.createTempFile("apprefer-support-", ".draft", context.cacheDir).also { draftFile = it }
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            val out = java.io.DataOutputStream(stream)
            out.writeUTF(route); out.writeUTF(id ?: ""); out.writeUTF(subject)
            val text = draft.toByteArray(Charsets.UTF_8); out.writeInt(text.size); out.write(text)
            out.writeInt(images.size); images.forEach { out.writeInt(it.size); out.write(it) }
            out.writeInt(pending.size)
            pending.forEach { it.write(out) }
            out.writeUTF(topic?.title ?: ""); out.writeUTF(homeClientMessageId)
            val stash = homeStash
            out.writeBoolean(stash != null)
            if (stash != null) {
                val stashText = stash.draft.toByteArray(Charsets.UTF_8); out.writeInt(stashText.size); out.write(stashText)
                out.writeInt(stash.images.size); stash.images.forEach { out.writeInt(it.size); out.write(it) }
                out.writeUTF(stash.topic?.title ?: "")
            }
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
                    topic = AppReferSupport.topics.firstOrNull { it.title == savedTopic && it.action == null }
                    homeClientMessageId = input.readUTF().takeIf { it.matches(Regex("[A-Za-z0-9_-]{8,64}")) } ?: homeClientMessageId
                }
                homeStash = null
                if (input.available() > 0 && input.readBoolean()) {
                    val stashSize = input.readInt(); require(stashSize in 0..1_000_000)
                    val stashText = String(ByteArray(stashSize).also { input.readFully(it) }, Charsets.UTF_8)
                    val stashCount = input.readInt(); require(stashCount in 0..4)
                    val stashImages = List(stashCount) { val length = input.readInt(); require(length in 1..700_000); ByteArray(length).also { input.readFully(it) } }
                    val stashTopic = input.readUTF().let { saved -> AppReferSupport.topics.firstOrNull { it.title == saved && it.action == null } }
                    if (route == "thread") homeStash = HomeStash(stashText, stashImages, stashTopic, null)
                }
            }
            draftFile = file; render()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        resume()
        if (focusOnOpen && route == "home") { focusOnOpen = false; focusField() }
    }
    override fun onDetachedFromWindow() { pause(); pending.forEach { it.failed = true }; saveDraft(); scope.cancel(); super.onDetachedFromWindow() }

    // MARK: - Dates

    private fun date(s: String): Long {
        val normalized = s.replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        return listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(normalized)?.time }.getOrNull()
        } ?: 0L
    }
    private fun clock(s: String): String = date(s).takeIf { it > 0 }?.let { android.text.format.DateFormat.getTimeFormat(context).format(Date(it)) } ?: ""
    /** "Today, 12:35", "Yesterday, 09:10" or "14 Oct, 18:02", like the iOS SDK. */
    private fun time(s: String): String {
        val ms = date(s).takeIf { it > 0 } ?: return ""
        val clock = android.text.format.DateFormat.getTimeFormat(context).format(Date(ms))
        val day = Calendar.getInstance().apply { timeInMillis = ms }
        val today = Calendar.getInstance()
        fun same(a: Calendar, b: Calendar) = a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
        val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
        return when {
            same(day, today) -> "Today, $clock"
            same(day, yesterday) -> "Yesterday, $clock"
            else -> "${android.text.format.DateFormat.getMediumDateFormat(context).format(Date(ms))}, $clock"
        }
    }
}

/** A round avatar: a person's initial, or an icon, on a diagonal gradient, optionally ringed in white. */
internal class SupportAvatarView(
    context: Context, private val colors: IntArray, private val initial: String?, icon: Int?, private val ring: Boolean,
) : View(context) {
    private val glyph = icon?.let { context.getDrawable(it)?.mutate()?.apply { setTint(Color.WHITE) } }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xE6FFFFFF.toInt() }
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

/** The home hero: the accent with three soft radial glows (light, hot and warm neighbours). */
internal class SupportHeroDrawable(private val p: SupportPalette, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        paint.shader = null; paint.color = p.accent; canvas.drawRect(b, paint)
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
        var y = 0
        lines.forEach { line ->
            val lineWidth = line.sumOf { it.measuredWidth } + gap * (line.size - 1)
            var x = if (alignEnd) width - lineWidth else 0
            line.forEach { child -> child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight); x += child.measuredWidth + gap }
            y += line.maxOf { it.measuredHeight } + gap
        }
    }
}
