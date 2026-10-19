package com.apprefer.sdk.support

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.ThumbnailUtils
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.apprefer.sdk.testimonial.TestimonialMedia
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// Photo/video testimonials (server: lib/support/testimonials.ts). A request is always made by
// something the app trusts (its backend, its coach tool, a person in Support, the AI within the
// app's policy, or automation); this SDK only answers one, for the account the support session is
// verified for (`AppRefer.support.verifyIdentity`). The reward and the publication permission text
// come from the request: the SDK never chooses a reward and never ties it to what is said. Taking
// part earns the reward; allowing public use is a separate, optional switch.

/** A photo/video request as the server sends it. */
data class AppReferTestimonialRequest(
    val requestId: String,
    /** open, submitted, declined, expired, withdrawn or unavailable. */
    val status: String,
    val campaignId: String?,
    val title: String?,
    val prompt: String?,
    /** "image" and/or "video". */
    val media: List<String>,
    val maxVideoSeconds: Int?,
    /** Null when the campaign has no reward. */
    val reward: Reward?,
    val permission: Permission?,
    val expiresAt: String?,
    val submittedAt: String?,
    /** granted, declined, revoked, or null before an answer. */
    val publication: String?,
    /** none (nothing earned yet), pending, delivered, fulfilled, failed, timed_out or not_eligible. */
    val rewardStatus: String?,
) {
    /** What the customer gets, as the app wrote it (e.g. "50 bonus credits"). */
    data class Reward(val type: String, val description: String?)
    /** Exactly what a "yes" to public use allows, shown word for word; [version] goes back with the answer. */
    data class Permission(val text: String, val scopes: List<String>, val version: String)

    val isOpen: Boolean get() = status == "open"
    val allowsImages: Boolean get() = "image" in media
    val allowsVideos: Boolean get() = "video" in media

    internal companion object {
        /** Null when the value isn't a request: no broken card is drawn. */
        fun from(raw: Any?): AppReferTestimonialRequest? {
            val m = raw as? Map<*, *> ?: return null
            val id = m["requestId"] as? String ?: return null
            val status = m["status"] as? String ?: return null
            val reward = (m["reward"] as? Map<*, *>)?.let { r -> (r["type"] as? String)?.let { Reward(it, r["description"] as? String) } }
            val permission = (m["permission"] as? Map<*, *>)?.let { p ->
                val text = p["text"] as? String; val version = p["version"] as? String
                if (text == null || version == null) null else Permission(text, (p["scopes"] as? List<*>).orEmpty().filterIsInstance<String>(), version)
            }
            return AppReferTestimonialRequest(
                id, status, m["campaignId"] as? String, m["title"] as? String, m["prompt"] as? String,
                (m["media"] as? List<*>).orEmpty().filterIsInstance<String>(), (m["maxVideoSeconds"] as? Number)?.toInt(), reward, permission,
                m["expiresAt"] as? String, m["submittedAt"] as? String, m["publication"] as? String, m["rewardStatus"] as? String,
            )
        }
    }
}

/** How a capture screen ended: submitted, declined, cancelled or unavailable; and the reward's state then. */
data class AppReferTestimonialResult(val requestId: String, val outcome: String, val rewardStatus: String?, val publication: String?)

/** A reward state change the SDK saw. On `fulfilled` your backend confirmed it: refresh credits/usage. */
data class AppReferTestimonialRewardUpdate(val requestId: String, val rewardStatus: String)

internal data class TestimonialSlot(val uploadId: String, val uploadUrl: String, val headers: Map<String, String>)

/** What the capture flow needs from the network (an interface so tests can stand in for it). */
internal interface TestimonialTransport {
    suspend fun request(id: String): AppReferTestimonialRequest
    suspend fun slot(id: String, contentType: String, size: Long): TestimonialSlot
    suspend fun put(file: File, slot: TestimonialSlot, size: Long, progress: (Double) -> Unit)
    suspend fun submit(id: String, uploadId: String, clientSubmissionId: String, publication: Boolean, version: String): AppReferTestimonialRequest
    suspend fun decline(id: String, dontAskAgain: Boolean): AppReferTestimonialRequest?
}

internal class LiveTestimonialTransport(private val api: SupportAPI) : TestimonialTransport {
    private val epoch = api.actionGeneration
    override suspend fun request(id: String) = api.testimonialRequest(id, epoch)
    override suspend fun slot(id: String, contentType: String, size: Long) = api.testimonialSlot(id, contentType, size, epoch)
    override suspend fun put(file: File, slot: TestimonialSlot, size: Long, progress: (Double) -> Unit) = withContext(Dispatchers.IO) {
        check(api.isCurrent(epoch)) { "Support reset" }
        val status = TestimonialMedia.upload(file, slot.uploadUrl, slot.headers, size) { progress(it) }
        check(api.isCurrent(epoch)) { "Support reset" }
        if (status !in 200..299) throw AppReferSupportException(status, "The upload didn't finish ($status)", AppReferSupportException.Reason.UPLOAD)
    }
    override suspend fun submit(id: String, uploadId: String, clientSubmissionId: String, publication: Boolean, version: String) =
        api.submitTestimonial(id, uploadId, clientSubmissionId, publication, version, epoch)
    override suspend fun decline(id: String, dontAskAgain: Boolean) = api.declineTestimonial(id, dontAskAgain, epoch)
}

/** The capture flow's state, separate from the views so it can be tested without UI. */
internal class TestimonialFlow(val requestId: String, private val transport: TestimonialTransport, private val changed: () -> Unit = {}) {
    enum class Phase { LOADING, READY, UPLOADING, CHECKING, DONE, UNAVAILABLE, FAILED }
    var phase = Phase.LOADING; private set
    var progress = 0.0; private set
    var request: AppReferTestimonialRequest? = null; private set
    var file: TestimonialMedia.Picked? = null; private set
    var publish = false
    /** The localized message for the last problem (null when none). */
    var notice: String? = null; private set
    /** The same on every retry: a lost answer never shares twice. */
    private val clientSubmissionId = "and_" + UUID.randomUUID().toString().replace("-", "")
    /** The upload of [file] that already reached storage (a retry only resubmits). */
    private var uploaded: Pair<String, String>? = null

    suspend fun load() {
        try {
            val r = transport.request(requestId)
            request = r
            phase = if (r.isOpen) Phase.READY else if (r.status == "submitted") Phase.DONE else Phase.UNAVAILABLE
            if (r.status == "submitted") AppReferTestimonials.note(requestId, r.rewardStatus ?: "none")
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            val refused = e is AppReferSupportException && (e.status == 404 || e.code == "IDENTITY_REQUIRED")
            notice = SupportStrings.text(if (refused) "support.testimonial.unavailable" else "support.testimonial.failed")
            phase = if (refused) Phase.UNAVAILABLE else Phase.FAILED
        }
        changed()
    }

    fun picked(f: TestimonialMedia.Picked) { file = f; notice = null; uploaded = null; if (phase == Phase.FAILED) phase = Phase.READY; changed() }

    fun pickFailed(code: String, seconds: Int) {
        notice = when (code) {
            "cancelled" -> notice
            "too_long" -> SupportStrings.text("support.testimonial.tooLong", "seconds" to seconds.toString())
            "too_large" -> SupportStrings.text("support.testimonial.tooLarge")
            else -> SupportStrings.text("support.error.imageUnreadable")
        }
        changed()
    }

    /** Uploads (unless this file already reached storage) and shares. Safe to call again after a failure. */
    suspend fun submit() {
        val r = request ?: return; val f = file ?: return; val version = r.permission?.version ?: return
        notice = null
        try {
            val uploadId = uploaded?.takeIf { it.first == f.path }?.second ?: run {
                phase = Phase.UPLOADING; progress = 0.0; changed()
                val slot = transport.slot(requestId, f.contentType, f.size)
                transport.put(File(f.path), slot, f.size) { p -> progress = p; changed() }
                uploaded = f.path to slot.uploadId
                slot.uploadId
            }
            phase = Phase.CHECKING; changed()
            val done = transport.submit(requestId, uploadId, clientSubmissionId, publish, version)
            request = done
            phase = Phase.DONE
            AppReferTestimonials.note(requestId, done.rewardStatus ?: "none")
        } catch (e: CancellationException) { throw e } catch (e: IllegalStateException) {
            // A reset or account switch while it ran: nothing more is sent for this account.
            phase = Phase.UNAVAILABLE
            notice = SupportStrings.text("support.testimonial.unavailable")
        } catch (e: Exception) {
            val code = (e as? AppReferSupportException)?.code
            if (code in setOf("TYPE_MISMATCH", "UNREADABLE_VIDEO", "VIDEO_TOO_LONG", "UPLOAD_INCOMPLETE", "UPLOAD_CHANGED", "UNKNOWN_UPLOAD")) uploaded = null
            if (code in setOf("REQUEST_CLOSED", "CAMPAIGN_ENDED", "IDENTITY_REQUIRED", "ALREADY_SUBMITTED") || (e as? AppReferSupportException)?.status == 404) {
                notice = SupportStrings.text("support.testimonial.unavailable"); phase = Phase.UNAVAILABLE
            } else {
                notice = when (code) {
                    "VIDEO_TOO_LONG" -> SupportStrings.text("support.testimonial.tooLong", "seconds" to (r.maxVideoSeconds ?: 0).toString())
                    "TOO_LARGE" -> SupportStrings.text("support.testimonial.tooLarge")
                    else -> SupportStrings.text("support.testimonial.failed")
                }
                phase = Phase.FAILED
            }
        }
        changed()
    }

    suspend fun decline(dontAskAgain: Boolean): AppReferTestimonialResult {
        val r = try { transport.decline(requestId, dontAskAgain) } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
        return AppReferTestimonialResult(requestId, "declined", r?.rewardStatus, null)
    }

    /** While the done screen is open: check the reward a few times (the app's backend applies it). */
    suspend fun followReward() {
        for (seconds in listOf(3L, 5L, 8L, 13L, 21L)) {
            val status = request?.rewardStatus
            if (phase != Phase.DONE || (status != "pending" && status != "delivered")) return
            delay(seconds * 1000)
            val fresh = try { transport.request(requestId) } catch (e: CancellationException) { throw e } catch (_: Exception) { return }
            request = fresh
            AppReferTestimonials.note(requestId, fresh.rewardStatus ?: "none")
            changed()
        }
    }

    val result: AppReferTestimonialResult
        get() = AppReferTestimonialResult(requestId, when (phase) { Phase.DONE -> "submitted"; Phase.UNAVAILABLE -> "unavailable"; else -> "cancelled" }, request?.rewardStatus, request?.publication)
}

/**
 * `AppRefer.support.testimonials`: show a photo/video request your backend (or its coach tool) made
 * (`POST /api/v1/apps/{appId}/support/testimonials/requests` returns the request id), list open
 * ones, and refresh credits in [onRewardUpdate] once your backend confirms the reward.
 */
object AppReferTestimonials {
    /** Called on the main thread when the SDK sees a reward state change. On `fulfilled`, refresh credits/usage. */
    @Volatile var onRewardUpdate: ((AppReferTestimonialRewardUpdate) -> Unit)? = null
    private val lastReward = ConcurrentHashMap<String, String>()
    internal val completions = ConcurrentHashMap<String, (AppReferTestimonialResult) -> Unit>()
    /** Runs the callback on the main thread (tests run it directly). */
    internal var dispatch: (() -> Unit) -> Unit = { r -> Handler(Looper.getMainLooper()).post(r) }

    /** Whether the camera can be offered for a video (the photo picker is always offered). */
    fun cameraAvailable(context: Context): Boolean = TestimonialMedia.cameraAvailable(context)

    /** Open requests for the verified account (empty when the session isn't verified). */
    suspend fun pending(): List<AppReferTestimonialRequest> = AppReferSupport.configuredApi().openTestimonials()

    /** One request; throws [AppReferSupportException] (code `IDENTITY_REQUIRED`) until the app verified this account. */
    suspend fun request(requestId: String): AppReferTestimonialRequest = AppReferSupport.configuredApi().testimonialRequest(requestId)

    /** The reward's state now; calls [onRewardUpdate] when it changed since the SDK last saw it. */
    suspend fun rewardStatus(requestId: String): String = (request(requestId).rewardStatus ?: "none").also { note(requestId, it) }

    /** Withdraws permission for public use of something already shared. The reward is unaffected. */
    suspend fun withdrawPublication(requestId: String) { AppReferSupport.configuredApi().revokeTestimonialPublication(requestId) }

    /** Shows the capture screen for a request your backend made. */
    @JvmOverloads fun present(context: Context, requestId: String, completion: ((AppReferTestimonialResult) -> Unit)? = null) {
        val token = UUID.randomUUID().toString()
        completion?.let { completions[token] = it }
        val intent = Intent(context, AppReferTestimonialActivity::class.java).putExtra("requestId", requestId).putExtra("token", token)
        if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    internal fun note(requestId: String, rewardStatus: String) {
        val previous = lastReward.put(requestId, rewardStatus)
        if (previous == rewardStatus || (previous == null && rewardStatus == "none")) return
        val update = AppReferTestimonialRewardUpdate(requestId, rewardStatus)
        dispatch { onRewardUpdate?.invoke(update) }
    }

    internal fun reset() { lastReward.clear() }

    // An open capture screen finishes on reset() (its own listener); what the SDK saw goes with the account.
    init { AppReferSupport.resetListeners.add { reset() } }
}

/** The capture screen: what's asked, the reward, pick or record, preview, optional public use, progress, result. */
class AppReferTestimonialActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var flow: TestimonialFlow
    private lateinit var body: LinearLayout
    private var token: String? = null
    private var finished = false
    private val resetListener: () -> Unit = { finish() }
    private val dark get() = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    private val c by lazy { SupportColors(dark) }
    private val p by lazy { SupportPalette(AppReferSupport.accentColor) }
    private fun s(key: String, vararg args: Pair<String, String>) = SupportStrings.text(key, *args)
    private fun dp(n: Number) = (resources.displayMetrics.density * n.toFloat()).toInt()

    override fun onCreate(state: Bundle?) {
        setTheme(if (dark) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        AppReferSupport.resetListeners.add(resetListener)
        token = intent.getStringExtra("token")
        val requestId = intent.getStringExtra("requestId") ?: return finish()
        // Upload progress arrives on a background thread: views are only touched on the main one.
        flow = TestimonialFlow(requestId, LiveTestimonialTransport(AppReferSupport.configuredApi())) { runOnUiThread { render() } }
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(16), dp(20), dp(28)) }
        setContentView(ScrollView(this).apply { setBackgroundColor(c.background); isFillViewport = true; addView(body) })
        render()
        scope.launch { flow.load() }
    }

    override fun onDestroy() {
        AppReferSupport.resetListeners.remove(resetListener)
        scope.cancel()
        if (::flow.isInitialized) {
            flow.file?.let { TestimonialMedia.discard(this, it.path) }
            if (!finished) deliver(flow.result)
        }
        super.onDestroy()
    }

    private fun deliver(result: AppReferTestimonialResult) {
        finished = true
        token?.let { AppReferTestimonials.completions.remove(it)?.invoke(result) }
    }

    private fun close(result: AppReferTestimonialResult = flow.result) { deliver(result); finish() }

    @Deprecated("Platform activity result bridge")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != PICK && requestCode != CAMERA) return
        val uri = data?.data
        val r = flow.request ?: return
        if (resultCode != RESULT_OK || uri == null) return
        scope.launch {
            try {
                val picked = withContext(Dispatchers.IO) { TestimonialMedia.normalize(this@AppReferTestimonialActivity, uri, r.allowsImages, r.allowsVideos, r.maxVideoSeconds ?: 30) }
                flow.file?.let { TestimonialMedia.discard(this@AppReferTestimonialActivity, it.path) }
                flow.picked(picked)
            } catch (e: TestimonialMedia.MediaException) {
                flow.pickFailed(e.code, e.seconds)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun pick(camera: Boolean) {
        val r = flow.request ?: return
        val intent = if (camera) TestimonialMedia.videoCaptureIntent(r.maxVideoSeconds ?: 30) else TestimonialMedia.libraryIntent(r.allowsImages, r.allowsVideos)
        try { startActivityForResult(intent, if (camera) CAMERA else PICK) } catch (_: Exception) { flow.pickFailed("unreadable", 0) }
    }

    private fun text(value: String, size: Float, color: Int = c.ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = top }
    private fun button(label: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 16f; minHeight = dp(50)
        setTextColor(if (primary) p.onAccent else p.accent)
        background = GradientDrawable().apply { cornerRadius = dp(25).toFloat(); setColor(if (primary) p.accent else supportAlpha(p.accent, 0.1f)) }
        setOnClickListener { onClick() }
    }
    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(c.card) }
    }

    private fun render() {
        if (!::body.isInitialized) return
        body.removeAllViews()
        val busy = flow.phase == TestimonialFlow.Phase.UPLOADING || flow.phase == TestimonialFlow.Phase.CHECKING
        body.addView(FrameLayout(this).apply {
            addView(text("✕", 18f).apply {
                contentDescription = s("support.testimonial.cancel"); setPadding(dp(10), dp(6), dp(10), dp(6))
                isEnabled = !busy; setOnClickListener { close() }
            }, FrameLayout.LayoutParams(-2, -2, Gravity.END))
        }, lp())
        val r = flow.request
        when (flow.phase) {
            TestimonialFlow.Phase.LOADING -> body.addView(ProgressBar(this), lp(dp(80)))
            TestimonialFlow.Phase.UNAVAILABLE -> {
                body.addView(text(s("support.testimonial.unavailable"), 15f, c.muted), lp(dp(24)))
                body.addView(button(s("support.testimonial.cancel"), true) { close() }, lp(dp(16)))
            }
            TestimonialFlow.Phase.DONE -> {
                body.addView(text(s("support.testimonial.done"), 26f, bold = true).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, lp(dp(12)))
                r?.reward?.description?.let { body.addView(rewardBox(it), lp(dp(14))) }
                if (r?.reward != null) body.addView(text(s(if (r.rewardStatus == "fulfilled") "support.testimonial.rewardDone" else "support.testimonial.rewardPending"), 15f), lp(dp(10)))
                body.addView(button(s("support.a11y.close"), true) { close() }, lp(dp(20)))
                if (!following) { following = true; scope.launch { flow.followReward() } }
            }
            else -> if (r == null) {
                body.addView(text(flow.notice ?: s("support.testimonial.failed"), 15f, c.muted), lp(dp(24)))
                body.addView(button(s("support.action.tryAgain"), true) { scope.launch { flow.load() } }, lp(dp(16)))
            } else form(r, busy)
        }
    }
    private var following = false

    private fun rewardBox(reward: String) = card().apply {
        addView(text(s("support.testimonial.reward", "reward" to reward), 15f, bold = true), lp())
        addView(text(s("support.testimonial.rewardNote"), 13f, c.muted), lp(dp(4)))
    }

    private fun form(r: AppReferTestimonialRequest, busy: Boolean) {
        body.addView(text(r.title ?: s("support.testimonial.open"), 26f, bold = true).apply { if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true }, lp(dp(8)))
        r.prompt?.let { body.addView(text(it, 16f), lp(dp(8))) }
        r.reward?.description?.let { body.addView(rewardBox(it), lp(dp(14))) }
        val f = flow.file
        if (f != null) {
            body.addView(preview(f), LinearLayout.LayoutParams(-1, dp(280)).apply { topMargin = dp(14) })
            if (!busy) body.addView(button(s("support.testimonial.retake"), false) { pick(false) }, lp(dp(10)))
            r.permission?.let { permission ->
                body.addView(card().apply {
                    addView(Switch(this@AppReferTestimonialActivity).apply {
                        text = s("support.testimonial.publish"); textSize = 15f; setTextColor(c.ink); isChecked = flow.publish; isEnabled = !busy
                        setOnCheckedChangeListener { _, on -> flow.publish = on }
                    }, lp())
                    addView(text(permission.text, 13.5f), lp(dp(6)))
                    addView(text(s("support.testimonial.publishHint"), 12.5f, c.muted), lp(dp(6)))
                }, lp(dp(14)))
            }
        } else {
            body.addView(button(s("support.testimonial.choose"), true) { pick(false) }, lp(dp(16)))
            if (r.allowsVideos && TestimonialMedia.cameraAvailable(this)) body.addView(button(s("support.testimonial.camera"), false) { pick(true) }, lp(dp(10)))
            if (r.allowsVideos) r.maxVideoSeconds?.let { body.addView(text(s("support.testimonial.tooLong", "seconds" to it.toString()), 13f, c.muted), lp(dp(8))) }
        }
        flow.notice?.let { body.addView(text(it, 14f, c.muted), lp(dp(12))) }
        when (flow.phase) {
            TestimonialFlow.Phase.UPLOADING -> {
                body.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100; progress = (flow.progress * 100).toInt() }, lp(dp(14)))
                body.addView(text(s("support.testimonial.uploading", "percent" to (flow.progress * 100).toInt().toString()), 14f, c.muted), lp(dp(4)))
            }
            TestimonialFlow.Phase.CHECKING -> body.addView(text(s("support.testimonial.checking"), 14f, c.muted), lp(dp(14)))
            else -> {
                if (f != null) body.addView(button(s(if (flow.phase == TestimonialFlow.Phase.FAILED) "support.action.tryAgain" else "support.testimonial.submit"), true) { scope.launch { flow.submit() } }, lp(dp(16)))
                body.addView(LinearLayout(this).apply {
                    gravity = Gravity.CENTER
                    addView(text(s("support.testimonial.decline"), 14f, c.muted, true).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { scope.launch { close(flow.decline(false)) } } })
                    addView(text(s("support.testimonial.dontAsk"), 14f, c.muted, true).apply { setPadding(dp(10), dp(10), dp(10), dp(10)); setOnClickListener { scope.launch { close(flow.decline(true)) } } })
                }, lp(dp(12)))
            }
        }
    }

    private fun preview(f: TestimonialMedia.Picked): View = ImageView(this).apply {
        contentDescription = s("support.testimonial.a11y.preview")
        scaleType = ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.BLACK)
        @Suppress("DEPRECATION")
        val bitmap = if (f.kind == "image") BitmapFactory.decodeFile(f.path) else ThumbnailUtils.createVideoThumbnail(f.path, MediaStore.Images.Thumbnails.MINI_KIND)
        bitmap?.let { setImageBitmap(it) }
    }

    private companion object {
        const val PICK = 8051
        const val CAMERA = 8052
    }
}
