package com.apprefer.sdk.support

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.apprefer.sdk.R
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/** Full-screen host. The view can also be embedded in any native layout. */
class AppReferSupportActivity : Activity() {
    private lateinit var chat: AppReferSupportView
    override fun onCreate(state: Bundle?) {
        setTheme(if (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
            android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
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
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
            chat.setOnApplyWindowInsetsListener { view, insets ->
                val edges = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
                view.setPadding(edges.left, edges.top, edges.right, edges.bottom)
                insets
            }
            chat.requestApplyInsets()
        }
        window.statusBarColor = chat.backgroundColor
        window.navigationBarColor = chat.backgroundColor
        if (Build.VERSION.SDK_INT >= 23 && resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK != Configuration.UI_MODE_NIGHT_YES) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
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

/** Native embeddable help home, compose and conversation screens. Call pause/resume with the host lifecycle. */
class AppReferSupportView @JvmOverloads constructor(
    context: Context, conversationId: String? = null, composing: String? = null,
) : LinearLayout(context) {
    var onClose: () -> Unit = {}
    var onPickImages: ((Int) -> Unit)? = null
    private val api = AppReferSupport.api(context.applicationContext)
    private val dark = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val backgroundColor: Int = if (dark) Color.rgb(18, 18, 20) else Color.WHITE
    private val ink = if (dark) Color.WHITE else Color.BLACK
    private val muted = if (dark) Color.LTGRAY else Color.GRAY
    private val surface = if (dark) Color.rgb(42, 42, 46) else Color.rgb(243, 242, 247)
    private val accent get() = AppReferSupport.accentColor
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var polling: Job? = null
    private var visible = false
    private var route = if (conversationId != null) "thread" else if (composing != null) "compose" else "home"
    private var id = conversationId
    private var subject = "Conversation"
    private val timeline = SupportTimeline { date(it) }
    private val cursor get() = timeline.cursor
    private var header: TextView? = null
    private var draft = composing ?: ""
    private var busy = false
    private var focusedCompose = false
    private var draftFile: java.io.File? = null
    private var error: String? = null
    private val images = mutableListOf<ByteArray>()
    private val messages get() = timeline.messages
    private val pending = mutableListOf<SupportPending>()
    private var conversations = emptyList<AppReferSupportConversation>()
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var field: EditText
    private var sender: View? = null

    init { orientation = VERTICAL; setBackgroundColor(backgroundColor); render() }
    private fun dp(n: Int) = (resources.displayMetrics.density * n).toInt()
    private fun shape(color: Int, radius: Int = 22, stroke: Boolean = false) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke) setStroke(dp(1), if (dark) 0xff55555a.toInt() else 0xffe6e6e9.toInt())
    }
    private fun text(value: String, size: Float = 16f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun column() = LinearLayout(context).apply { orientation = VERTICAL }
    private fun row() = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun padded(v: View, n: Int) { v.setPadding(dp(n), dp(n), dp(n), dp(n)) }
    private fun button(label: String, description: String = label, action: () -> Unit): TextView = text(label, 20f).apply {
        gravity = Gravity.CENTER; minWidth = dp(48); minHeight = dp(48); background = shape(if (dark) 0xee303034.toInt() else 0xfafafafa.toInt(), 24, true); elevation = dp(1).toFloat()
        contentDescription = description; isClickable = true; isFocusable = true; setOnClickListener { action() }
    }
    private fun icon(res: Int, tint: Int, size: Int = 22) = ImageView(context).apply {
        setImageResource(res); setColorFilter(tint); layoutParams = LayoutParams(dp(size), dp(size))
    }
    private val glass get() = if (dark) 0xee303034.toInt() else 0xfafafafa.toInt()
    /** Round 48 dp button with a vector icon, on a glass-like surface or filled with [fill]. */
    private fun iconButton(res: Int, description: String, tint: Int = ink, fill: Int? = null, action: () -> Unit) = FrameLayout(context).apply {
        background = if (fill != null) shape(fill, 24) else shape(glass, 24, true); elevation = if (fill == null) dp(1).toFloat() else 0f
        addView(icon(res, tint, 20).apply { layoutParams = FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER) })
        contentDescription = description; isClickable = true; isFocusable = true; setOnClickListener { action() }
        layoutParams = LayoutParams(dp(48), dp(48))
    }
    private fun title() = AppReferSupport.resolvedTitle(context)
    private fun top() {
        val bar = row(); bar.setPadding(dp(16), dp(10), dp(16), dp(10))
        bar.addView(iconButton(if (route == "home") R.drawable.apprefer_support_close else R.drawable.apprefer_support_back, if (route == "home") "Close" else "Back") { if (!goBack()) onClose() })
        val pill = column(); pill.setPadding(dp(18), dp(7), dp(18), dp(7)); pill.gravity = Gravity.CENTER; pill.background = shape(glass, 28, true); pill.elevation = dp(1).toFloat()
        pill.addView(text(when(route) { "home" -> "Help"; "compose" -> "New message"; else -> subject }, 17f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; header = this })
        if (route != "home") pill.addView(text(title(), 12f, muted).apply { maxLines = 1; gravity = Gravity.CENTER })
        // The title pill sizes to its text and sits in the middle, like the iOS bar.
        val middle = FrameLayout(context).apply { addView(pill, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER)) }
        bar.addView(middle, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
        if (route == "compose") { sender = sendButton { send() }; bar.addView(sender) }
        else bar.addView(Space(context), LayoutParams(dp(48), dp(1)))
        addView(bar)
    }
    private fun sendButton(action: () -> Unit) = iconButton(R.drawable.apprefer_support_send, "Send", Color.WHITE, accent, action)
    private fun updateSend() { sender?.apply { isEnabled = !busy && (draft.isNotBlank() || images.isNotEmpty()); alpha = if (isEnabled) 1f else .35f } }
    private fun render() {
        val restoreFocus = ::field.isInitialized && field.hasFocus()
        removeAllViews(); sender = null; top()
        body = column(); padded(body, 20)
        scroll = ScrollView(context).apply { isFillViewport = false; addView(body) }
        addView(scroll, LayoutParams(-1, 0, 1f))
        when (route) { "home" -> renderHome(); "compose" -> renderCompose(); else -> { renderMessages(); composer() } }
        error?.let { body.addView(text(it, 13f, muted).apply { setOnClickListener { resume() } }) }
        updateSend()
        if (restoreFocus && route != "home" && ::field.isInitialized) field.requestFocus()
    }
    private fun renderHome() {
        body.addView(text(title(), 16f, accent, true).apply { setPadding(0, dp(20), 0, dp(8)) })
        body.addView(text("Hi ${AppReferSupport.name?.split(" ")?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "there"} 👋", 34f, bold = true))
        body.addView(text(AppReferSupport.replyTimeText, 17f, muted).apply { setPadding(0, dp(8), 0, dp(26)) })
        if (conversations.isNotEmpty()) body.addView(text(if (conversations.size == 1) "Your conversation" else "Your conversations", 13f, muted, true))
        val list = column().apply { background = shape(surface) }; body.addView(list)
        conversations.forEach { c ->
            val item = row(); padded(item, 12)
            item.addView(FrameLayout(context).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((accent and 0x00ffffff) or 0x26000000) }
                addView(icon(if (c.status == "closed") R.drawable.apprefer_support_check else R.drawable.apprefer_support_bubble, accent, 18).apply { layoutParams = FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER) })
            }, LayoutParams(dp(40), dp(40)).apply { setMargins(0, 0, dp(12), 0) })
            val labels = column(); labels.addView(text(c.subject, bold = c.unread > 0).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            labels.addView(text(c.preview, 14f, muted).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
            item.addView(labels, LayoutParams(0, -2, 1f))
            item.addView(text(if (c.unread > 0) "${c.unread}" else time(c.lastMessageAt), 12f, if(c.unread > 0) Color.WHITE else muted).apply {
                if (c.unread > 0) { background = shape(accent); padded(this, 6); contentDescription = "${c.unread} unread" }
            })
            item.setOnClickListener { id = c.id; subject = c.subject; timeline.reset(); navigate("thread") }; list.addView(item)
        }
        val cta = row().apply {
            background = shape(accent); setPadding(dp(20), dp(14), dp(20), dp(14)); isClickable = true; isFocusable = true
            contentDescription = "Send us a message, Screenshots welcome"; setOnClickListener { navigate("compose") }
            addView(icon(R.drawable.apprefer_support_forum, Color.WHITE, 24))
            addView(column().apply {
                addView(text("Send us a message", 17f, Color.WHITE, true))
                addView(text("Screenshots welcome", 12f, Color.WHITE).apply { alpha = .85f })
            }, LayoutParams(0, -2, 1f).apply { setMargins(dp(12), 0, dp(8), 0) })
            addView(icon(R.drawable.apprefer_support_chevron, Color.WHITE, 20))
        }
        addView(cta, LayoutParams(-1, -2).apply { setMargins(dp(20), dp(12), dp(20), dp(16)) })
    }
    private fun editor(placeholder: String) = EditText(context).apply {
        setText(draft); hint = placeholder; textSize = 17f; setTextColor(ink); setHintTextColor(muted)
        background = null; gravity = Gravity.TOP; inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { draft = s.toString(); updateSend() }
            override fun afterTextChanged(s: Editable?) {}
        })
    }
    private fun renderCompose() {
        field = editor("How can we help? Tell us what happened.").apply { minHeight = dp(160) }; body.addView(field)
        if (!focusedCompose) {
            focusedCompose = true
            field.post {
                if (visible && route == "compose") {
                    field.requestFocus()
                    (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).showSoftInput(field, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
        attachmentStrip(body)
        pending.filter { it.conversationId == null }.forEach { p ->
            body.addView(text(p.text, 17f))
            p.images.forEach { body.addView(image(it), LayoutParams(dp(72), dp(84))) }
            body.addView(text(if (p.failed) "Not sent · Tap to retry" else "Sending…", 12f, muted).apply {
                if (p.failed) setOnClickListener { deliver(p) }
            })
        }
        val c = AppReferSupport.deviceContext(context)
        body.addView(text("Sent with: app ${c["appVersion"]} · ${c["device"]} · ${c["os"]}", 12f, muted).apply { setPadding(0, dp(16), 0, 0) })
    }
    private fun composer() {
        val panel = column(); padded(panel, 12)
        if (images.isNotEmpty()) attachmentStrip(panel)
        val bar = row(); bar.addView(iconButton(R.drawable.apprefer_support_add, "Add screenshot") { pick() })
        field = editor("Message").apply { minLines = 1; maxLines = 5; background = shape(surface, 24, true); padded(this, 12) }
        bar.addView(field, LayoutParams(0, -2, 1f).apply { setMargins(dp(10), 0, dp(10), 0) })
        sender = sendButton { send() }; bar.addView(sender); panel.addView(bar); addView(panel)
    }
    private fun pick() { if (images.size < 4 && !busy) onPickImages?.invoke(4 - images.size) }
    private fun attachmentStrip(parent: LinearLayout) {
        val strip = row()
        images.forEachIndexed { index, bytes ->
            val tile = column(); tile.addView(image(bytes), LayoutParams(dp(72), dp(84)))
            tile.addView(iconButton(R.drawable.apprefer_support_close, "Remove screenshot", Color.WHITE, 0x99000000.toInt()) { if (!busy) { images.removeAt(index); render() } })
            strip.addView(tile)
        }
        if (images.size < 4) strip.addView(column().apply {
            gravity = Gravity.CENTER; background = shape((accent and 0x00ffffff) or 0x1a000000, 12)
            contentDescription = "Add screenshot"; isClickable = true; isFocusable = true; setOnClickListener { pick() }
            addView(icon(R.drawable.apprefer_support_photo, accent, 26).apply { layoutParams = LayoutParams(dp(26), dp(26)).apply { gravity = Gravity.CENTER_HORIZONTAL } })
            addView(text("Screenshot", 13f, accent, true).apply { setPadding(0, dp(6), 0, 0) })
        }, LayoutParams(dp(96), dp(96)))
        parent.addView(HorizontalScrollView(context).apply { addView(strip) })
    }
    // Large images stay out of Binder saved-state parcels during Activity recreation.
    internal fun saveDraft(): String? = runCatching {
        val file = draftFile ?: java.io.File.createTempFile("apprefer-support-", ".draft", context.cacheDir).also { draftFile = it }
        val atomic = android.util.AtomicFile(file)
        val stream = atomic.startWrite()
        try {
        val out = java.io.DataOutputStream(stream)
        run {
            out.writeUTF(route); out.writeUTF(id ?: ""); out.writeUTF(subject)
            val text = draft.toByteArray(Charsets.UTF_8); out.writeInt(text.size); out.write(text)
            out.writeInt(images.size); images.forEach { out.writeInt(it.size); out.write(it) }
            out.writeInt(pending.size)
            pending.forEach { it.write(out) }
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
                route = input.readUTF(); require(route in listOf("home", "compose", "thread"))
                id = input.readUTF().takeIf { it.isNotEmpty() }; subject = input.readUTF()
                val size = input.readInt(); require(size in 0..1_000_000)
                draft = String(ByteArray(size).also { input.readFully(it) }, Charsets.UTF_8)
                images.clear(); val count = input.readInt(); require(count in 0..4)
                repeat(count) { val length = input.readInt(); require(length in 1..700_000); images.add(ByteArray(length).also { input.readFully(it) }) }
                pending.clear()
                if (input.available() > 0) {
                    val countPending = input.readInt(); require(countPending in 0..100)
                    repeat(countPending) { pending.add(SupportPending.read(input)) }
                }
            }
            draftFile = file; render()
        }
    }
    fun addImages(uris: List<Uri>) {
        scope.launch {
            try {
                val room = 4 - images.size
                val bytes = withContext(Dispatchers.IO) { uris.take(room).map { SupportImages.jpeg(context, it) } }
                images.addAll(bytes.take(4 - images.size)); error = null
            } catch (e: Exception) { error = e.message }
            render()
        }
    }
    private fun image(bytes: ByteArray) = ImageView(context).apply {
        setImageBitmap(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)); scaleType = ImageView.ScaleType.CENTER_CROP
        background = shape(surface, 16); clipToOutline = true; contentDescription = "Screenshot"
    }
    private fun renderMessages() {
        body.removeAllViews()
        if (messages.isEmpty() && pending.none { it.conversationId == id } && error == null) body.addView(ProgressBar(context))
        messages.forEachIndexed { i, m ->
            if (i == 0 || date(m.createdAt) - date(messages[i-1].createdAt) > 3_600_000) body.addView(text(time(m.createdAt), 12f, muted).apply { gravity = Gravity.CENTER; padded(this, 8) })
            val mine = m.from == "customer"
            val group = column().apply { gravity = if (mine) Gravity.END else Gravity.START }
            if (!mine && (i == 0 || messages[i-1].from != m.from || messages[i-1].authorName != m.authorName)) group.addView(text(
                if (m.from == "bot") "${title()} · instant answer" else m.authorName?.takeUnless { it.contains("@") }?.split(" ")?.firstOrNull() ?: "Team", 12f, muted, true))
            m.attachments.filter { it.contentType?.startsWith("image/") == true }.forEach { a ->
                val picture = ImageView(context).apply { background = shape(surface, 16); clipToOutline = true; scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = a.name ?: "Screenshot" }
                group.addView(picture, LayoutParams(dp(180), dp(240)))
                scope.launch { try {
                    val bitmap = withContext(Dispatchers.IO) {
                        a.url?.let { SupportImages.remote(it) }
                    }
                    picture.setImageBitmap(bitmap)
                } catch (_: Exception) {} }
            }
            if (m.text.isNotEmpty()) group.addView(bubble(m.text, mine))
            body.addView(group, LayoutParams(-1, -2).apply { setMargins(if(mine) dp(48) else 0, dp(4), if(mine) 0 else dp(48), dp(4)) })
        }
        pending.filter { it.conversationId == id }.forEach { p ->
            val group = column().apply { gravity = Gravity.END; alpha = .6f }
            p.images.forEach { group.addView(image(it), LayoutParams(dp(180), dp(240))) }
            if (p.text.isNotEmpty()) group.addView(bubble(p.text, true))
            group.addView(text(if(p.failed) "Not sent · Tap to retry" else "Sending…", 12f, muted).apply { if(p.failed) setOnClickListener { p.failed = false; deliver(p) } })
            body.addView(group)
        }
        if (messages.lastOrNull()?.from == "customer" && pending.none { it.conversationId == id }) body.addView(text("We'll notify you when the team replies.", 12f, muted).apply { gravity = Gravity.CENTER; padded(this, 8) })
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }
    private fun bubble(value: String, mine: Boolean) = text(value, 17f, if (mine) Color.WHITE else ink).apply {
        background = shape(if(mine) accent else surface, 20); padded(this, 14); setTextIsSelectable(true)
    }
    private fun merge(incoming: List<AppReferSupportMessage>) {
        timeline.merge(incoming); renderMessages()
    }
    private fun send() {
        if (busy || (draft.isBlank() && images.isEmpty())) return
        val p = SupportPending(if (route == "compose") null else id, draft.trim(), images.toList())
        pending.add(p); draft = ""; images.clear(); saveDraft(); render(); deliver(p)
    }
    private fun deliver(p: SupportPending) {
        if (p.conversationId == null) busy = true
        p.failed = false
        saveDraft(); render()
        scope.launch {
            try {
                // Every attempt uses fresh slots, but keeps the original send identity.
                val ids = p.images.map { api.upload(it) }
                if (p.conversationId == null) {
                    val t = api.start(p.text, ids, p.clientMessageId)
                    id = t.conversation.id; subject = t.conversation.subject
                    timeline.reset(); timeline.merge(t.messages)
                    pending.remove(p); navigate("thread")
                } else {
                    val m = api.send(p.conversationId, p.text, ids, p.clientMessageId)
                    pending.remove(p)
                    if (route == "thread" && id == p.conversationId) merge(listOf(m))
                }
            } catch (e: CancellationException) { p.failed = true; throw e }
            catch (e: Exception) { p.failed = true; error = e.message }
            finally { busy = false; if (isAttachedToWindow) { saveDraft(); render() } }
        }
    }
    private fun navigate(next: String) {
        if (next == "compose") focusedCompose = false
        if (next == "home" || route == "compose") {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager).hideSoftInputFromWindow(windowToken, 0)
        }
        route = next; error = null; render(); if (visible) startPolling()
    }
    fun goBack(): Boolean { if (busy) return true; if(route == "home") return false; draft = ""; images.clear(); navigate("home"); return true }
    fun pause() { visible = false; polling?.cancel(); polling = null }
    fun resume() { visible = true; startPolling() }
    private fun startPolling() {
        polling?.cancel()
        polling = scope.launch {
            do {
                try {
                    if (route == "home") { conversations = api.conversations().sortedWith(compareBy<AppReferSupportConversation> { it.status == "closed" }.thenByDescending { it.lastMessageAt }); error = null; render() }
                    else if (route == "thread") {
                        val t = api.thread(requireNotNull(id), cursor)
                        subject = t.conversation.subject; header?.text = subject
                        val empty = messages.isEmpty()
                        timeline.merge(t.messages, fromLoad = true)
                        if (empty) render() else renderMessages()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { error = e.message; if(route == "home") render() else if(route == "thread" && messages.isEmpty()) { body.removeAllViews(); body.addView(text(error ?: "Could not load conversation", 14f, muted)) } }
                delay(if(route == "thread") 4_000 else 15_000)
            } while (route != "compose")
        }
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if(!scope.isActive) scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); resume() }
    override fun onDetachedFromWindow() { pause(); pending.forEach { it.failed = true }; saveDraft(); scope.cancel(); super.onDetachedFromWindow() }
    private fun date(s: String): Long {
        val normalized = s.replace(Regex("Z$"), "+0000").replace(Regex("([+-]\\d{2}):(\\d{2})$"), "$1$2")
        return listOf("yyyy-MM-dd'T'HH:mm:ss.SSSZ", "yyyy-MM-dd'T'HH:mm:ssZ").firstNotNullOfOrNull { pattern ->
            runCatching { SimpleDateFormat(pattern, Locale.US).parse(normalized)?.time }.getOrNull()
        } ?: 0L
    }
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
