package com.apprefer.sdk.support

import com.apprefer.sdk.internal.JsonCodec
import com.apprefer.sdk.testimonial.TestimonialMedia
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Photo/video testimonials (0.13.0): model, capture flow, retries, API routes and reward updates. */
class SupportTestimonialTest {
    @Before fun strings() { SupportStringsFixture.install(); SupportStrings.forcedLanguage = "en"; AppReferTestimonials.dispatch = { it() }; AppReferTestimonials.reset() }
    @After fun reset() { SupportStrings.forcedLanguage = null; SupportStringsFixture.uninstall(); AppReferTestimonials.onRewardUpdate = null }

    private fun json(status: String = "open", rewardStatus: String = "none", publication: String? = null) =
        """{"requestId":"tsr_0123456789abcdef0123456789abcdef","campaignId":"progress_story","status":"$status","title":"Share your progress","prompt":"Show us what you made.","media":["image","video"],"maxVideoSeconds":30,"reward":{"type":"credits","description":"50 bonus credits"},"permission":{"text":"You may show this in the app.","scopes":["in_app"],"version":"0123456789abcdef"},"expiresAt":"2026-11-02T12:00:00.000Z","submittedAt":null,"publication":${publication?.let { "\"$it\"" } ?: "null"},"rewardStatus":"$rewardStatus"}"""
    private fun request(j: String = json()) = AppReferTestimonialRequest.from(JsonCodec.decodeObject(j))!!

    private class Fake(var current: AppReferTestimonialRequest) : TestimonialTransport {
        var slots = 0; var puts = 0
        val submits = mutableListOf<List<Any>>()
        var failSubmit: Exception? = null
        override suspend fun request(id: String) = current
        override suspend fun slot(id: String, contentType: String, size: Long) = TestimonialSlot("tup_${"0".repeat(31)}${++slots}", "https://r2.test/u", mapOf("Content-Type" to contentType))
        override suspend fun put(file: File, slot: TestimonialSlot, size: Long, progress: (Double) -> Unit) { puts++; progress(0.5); progress(1.0) }
        override suspend fun submit(id: String, uploadId: String, clientSubmissionId: String, publication: Boolean, version: String): AppReferTestimonialRequest {
            submits.add(listOf(uploadId, clientSubmissionId, publication, version))
            failSubmit?.let { failSubmit = null; throw it }
            return current.copy(status = "submitted", rewardStatus = "pending", publication = if (publication) "granted" else "declined")
        }
        override suspend fun decline(id: String, dontAskAgain: Boolean) = current.copy(status = "declined")
    }
    private val photo = TestimonialMedia.Picked("/tmp/apprefer-testimonial-a.jpg", "image/jpeg", "image", 2000, null)

    @Test fun decodesRequestsAndMessagesAndSkipsUnreadableOnes() {
        val r = request()
        assertTrue(r.isOpen && r.allowsImages && r.allowsVideos)
        assertEquals("50 bonus credits", r.reward?.description)
        assertEquals("0123456789abcdef", r.permission?.version)
        val card = AppReferTestimonialRequest.from(mapOf("requestId" to "tsr_x", "status" to "unavailable"))!!
        assertFalse(card.isOpen); assertNull(card.reward)
        assertNull(AppReferTestimonialRequest.from(mapOf("status" to "open")))
        val m = AppReferSupportMessage(JsonCodec.decodeObject("""{"id":"m1","from":"team","authorName":"Jay","text":"Would you share one?","createdAt":"x","attachments":[],"testimonial":${json()}}"""))
        assertEquals("tsr_0123456789abcdef0123456789abcdef", m.testimonial?.requestId)
    }

    @Test fun aLostAnswerResubmitsTheSameUploadWithTheSameClientId() = runBlocking {
        val t = Fake(request())
        val flow = TestimonialFlow(t.current.requestId, t)
        flow.load()
        assertEquals(TestimonialFlow.Phase.READY, flow.phase)
        flow.picked(photo)
        flow.publish = false
        t.failSubmit = java.io.IOException("connection reset")
        flow.submit()
        assertEquals(TestimonialFlow.Phase.FAILED, flow.phase)
        assertEquals("Couldn't share it, nothing was sent. Check your connection and try again.", flow.notice)
        flow.submit()
        assertEquals(TestimonialFlow.Phase.DONE, flow.phase)
        assertEquals(1, t.slots); assertEquals(1, t.puts)
        assertEquals(t.submits[0][0], t.submits[1][0])
        assertEquals("same clientSubmissionId", t.submits[0][1], t.submits[1][1])
        assertEquals(false, t.submits[1][2])
        assertEquals("0123456789abcdef", t.submits[1][3])
        assertEquals(AppReferTestimonialResult(t.current.requestId, "submitted", "pending", "declined"), flow.result)
    }

    @Test fun publicUseStartsOnButOnlyTheFinalShareRecordsIt() = runBlocking {
        val t = Fake(request())
        val flow = TestimonialFlow(t.current.requestId, t)
        assertTrue("public use starts on", flow.publish)
        flow.load(); flow.picked(photo)
        assertEquals("the app's registered text is what the switch shows", "You may show this in the app.", flow.request?.permission?.text)
        assertTrue("opening and picking record nothing", t.submits.isEmpty())
        flow.submit()
        assertEquals(listOf(true, "0123456789abcdef"), t.submits.single().subList(2, 4))
        assertEquals(AppReferTestimonialResult(t.current.requestId, "submitted", "pending", "granted"), flow.result)
        val declined = TestimonialFlow("tsr_x", Fake(request()))
        declined.load(); declined.picked(photo)
        assertNull("declining sends no permission answer", declined.decline(false).publication)
    }

    @Test fun theSubmitButtonSaysWhetherItAllowsSharing() = runBlocking {
        val t = Fake(request())
        val flow = TestimonialFlow(t.current.requestId, t)
        flow.load(); flow.picked(photo)
        assertEquals("support.testimonial.submitPublic", flow.submitLabelKey)
        assertEquals("Send and allow sharing", SupportStrings.text(flow.submitLabelKey))
        flow.publish = false
        assertEquals("Send privately", SupportStrings.text(flow.submitLabelKey))
        t.failSubmit = java.io.IOException("connection reset")
        flow.submit()
        assertEquals(TestimonialFlow.Phase.FAILED, flow.phase)
        assertEquals("a retry still names what it sends", "Send privately", SupportStrings.text(flow.submitLabelKey))
        flow.submit()
        assertEquals("the label matches the answer sent", listOf(false, false), t.submits.map { it[2] })
        SupportStrings.forcedLanguage = "de"
        assertEquals("Senden und Teilen erlauben", SupportStrings.text("support.testimonial.submitPublic"))
        assertEquals("Privat senden", SupportStrings.text("support.testimonial.submitPrivate"))
    }

    @Test fun aRequestWithoutPermissionTextIsNeverSubmittedWithAnAssumedAnswer() = runBlocking {
        val old = json().replace(""""permission":{"text":"You may show this in the app.","scopes":["in_app"],"version":"0123456789abcdef"},""", "")
        val t = Fake(request(old))
        val flow = TestimonialFlow(t.current.requestId, t)
        flow.load()
        assertNull(flow.request?.permission)
        assertEquals("no switch, so the button claims neither sharing nor privacy", "support.testimonial.submit", flow.submitLabelKey)
        flow.picked(photo); flow.submit()
        assertTrue("no permission text means no answer is sent or inferred", t.submits.isEmpty())
        assertEquals(0, t.slots)
    }

    @Test fun aRefusedFileIsUploadedAgainAndAClosedRequestEndsTheFlow() = runBlocking {
        val t = Fake(request())
        val flow = TestimonialFlow(t.current.requestId, t)
        flow.load(); flow.picked(photo)
        t.failSubmit = AppReferSupportException(422, "The file isn't a supported photo or video.", "TYPE_MISMATCH")
        flow.submit(); flow.submit()
        assertEquals(2, t.slots)
        val other = TestimonialFlow("tsr_y", t)
        other.load(); other.picked(photo)
        t.failSubmit = AppReferSupportException(409, "verify", "IDENTITY_REQUIRED")
        other.submit()
        assertEquals(TestimonialFlow.Phase.UNAVAILABLE, other.phase)
        assertEquals("This request is no longer available", other.notice)
    }

    @Test fun anotherAccountsOrUnverifiedRequestIsUnavailable() = runBlocking {
        val t = object : TestimonialTransport by Fake(request()) {
            override suspend fun request(id: String): AppReferTestimonialRequest = throw AppReferSupportException(409, "verify", "IDENTITY_REQUIRED")
        }
        val flow = TestimonialFlow("tsr_x", t)
        flow.load()
        assertEquals(TestimonialFlow.Phase.UNAVAILABLE, flow.phase)
        assertEquals("unavailable", flow.result.outcome)
    }

    @Test fun pickerProblemsAreExplainedAndDeclineEarnsNothing() = runBlocking {
        val t = Fake(request())
        val flow = TestimonialFlow(t.current.requestId, t)
        flow.load()
        flow.pickFailed("too_long", 30)
        assertEquals("Videos can be up to 30 seconds.", flow.notice)
        flow.pickFailed("too_large", 0)
        assertEquals("This file is too large to share.", flow.notice)
        // A camera that went away between the button and the tap is not an error; a declined prompt is explained.
        flow.pickFailed("unavailable", 0)
        assertEquals("This file is too large to share.", flow.notice)
        flow.pickFailed("camera_denied", 0)
        assertEquals("Camera access is off. You can choose from your library instead.", flow.notice)
        assertEquals(AppReferTestimonialResult(t.current.requestId, "declined", "none", null), flow.decline(true))
    }

    @Test fun theRewardLineFollowsTheBackendsReceiptAndTheRewardType() {
        fun line(status: String, type: String) = SupportStrings.text(request(json(status = "submitted", rewardStatus = status)).copy(reward = AppReferTestimonialRequest.Reward(type, "x")).rewardStatusKey)
        // Earned but unconfirmed: never "added".
        for (s in listOf("pending", "delivered", "none")) assertEquals("You've earned it. It shows as confirmed here once it has been applied.", line(s, "credits"))
        for (type in listOf("credits", "coach_usage", "subscription_time")) assertEquals("Your reward has been added to your account.", line("fulfilled", type))
        // Goods, gifts and discounts are handled by the app: confirmed, no shipping or email promise.
        for (type in listOf("merch", "gift", "discount", "manual")) assertEquals("Your reward is confirmed.", line("fulfilled", type))
        assertEquals("Your reward hasn't been confirmed yet. Support can see this and can send it again.", line("failed", "merch"))
        assertEquals(line("failed", "merch"), line("timed_out", "credits"))
        assertEquals("You've already taken part in this, so there's no new reward this time.", line("not_eligible", "credits"))
    }

    @Test fun rewardUpdatesReachTheAppOncePerChange() {
        val seen = mutableListOf<String>()
        AppReferTestimonials.onRewardUpdate = { seen.add(it.rewardStatus) }
        listOf("none", "pending", "pending", "fulfilled").forEach { AppReferTestimonials.note("tsr_a", it) }
        assertEquals(listOf("pending", "fulfilled"), seen)
    }

    private class Store : SupportStore { override var token: String? = null; override var profile: String? = null }

    @Test fun apiRoutesSendOnlyWhatTheServerExpects() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val bodies = mutableListOf<Map<String, Any?>>()
        val api = SupportAPI({ "pk_live_key" }, Store(), { mapOf("appUserId" to "user_42") }, { emptyMap() }, SupportTransport { method, url, headers, bytes ->
            if (url.endsWith("/session")) return@SupportTransport SupportResponse(201, """{"session":"s1","created":true}""")
            calls.add(method to url); bodies.add(JsonCodec.decodeObject(bytes?.toString(Charsets.UTF_8)))
            assertTrue(headers["X-AppRefer-Support-Features"]!!.contains("testimonials"))
            when {
                url.endsWith("/uploads") -> SupportResponse(201, """{"uploadId":"tup_${"1".repeat(32)}","uploadUrl":"https://r2.test/u","headers":{"Content-Type":"image/jpeg"},"expiresIn":900}""")
                url.endsWith("/testimonials") -> SupportResponse(200, """{"requests":[${json()},{"x":1}]}""")
                else -> SupportResponse(200, """{"request":${json(status = "submitted", rewardStatus = "pending")}}""")
            }
        }, identityCleared = { false })
        val epoch = api.actionGeneration
        assertEquals(1, api.openTestimonials().size)
        assertEquals("submitted", api.testimonialRequest("tsr_a", epoch).status)
        assertEquals("tup_${"1".repeat(32)}", api.testimonialSlot("tsr_a", "image/jpeg", 2000, epoch).uploadId)
        api.submitTestimonial("tsr_a", "tup_1", "and_client", true, "0123456789abcdef", epoch)
        api.declineTestimonial("tsr_a", true, epoch)
        api.revokeTestimonialPublication("tsr_a")
        assertEquals(listOf("GET", "GET", "POST", "POST", "POST", "POST"), calls.map { it.first })
        assertTrue(calls[2].second.endsWith("/api/support/v1/testimonials/tsr_a/uploads"))
        assertEquals(mapOf("uploadId" to "tup_1", "clientSubmissionId" to "and_client", "publication" to true, "permissionVersion" to "0123456789abcdef"), bodies[3])
        assertEquals(mapOf("dontAskAgain" to true), bodies[4])
        assertEquals(mapOf("publication" to "revoked"), bodies[5])
    }

    @Test fun pickersAndUploadsAreBounded() {
        assertEquals(15L * 1024 * 1024, TestimonialMedia.MAX_IMAGE_BYTES)
        assertEquals(150L * 1024 * 1024, TestimonialMedia.MAX_VIDEO_BYTES)
        val e = try { TestimonialMedia.upload(File("/nonexistent"), "http://r2.test/u", emptyMap(), 1, null); null } catch (x: java.io.IOException) { x }
        assertEquals("upload address must be https", e?.message)
    }

    /** TestimonialMedia.java is shared by copy with the Flutter plugin and the React Native module. */
    @Test fun sharedMediaHelperMatchesTheManifest() {
        val source = File("src/main/java/com/apprefer/sdk/testimonial/TestimonialMedia.java").readText()
        val normalized = source.replace("\r\n", "\n").replaceFirst(Regex("^package [^\n]*\n"), "")
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(normalized.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        val manifest = org.json.JSONObject(File("../shared-sources.json").readText())
        assertEquals("TestimonialMedia.java changed without the shared copies: run node scripts/check-sdk-shared-sources.mjs in the parent repo",
            manifest.getString("testimonialMediaJava"), hash)
    }

    @Test fun everyLanguageHasTheTestimonialStrings() {
        assertEquals("For taking part: 50 bonus credits", SupportStrings.text("support.testimonial.reward", "reward" to "50 bonus credits"))
        assertEquals("You get it for taking part, whatever you say and whether or not you allow reuse.", SupportStrings.text("support.testimonial.rewardNote"))
        assertEquals("Good, bad or mixed: we want your honest take.", SupportStrings.text("support.testimonial.honest"))
        assertEquals("Take a photo", SupportStrings.text("support.testimonial.takePhoto"))
        assertEquals("Record a video", SupportStrings.text("support.testimonial.recordVideo"))
    }
}
