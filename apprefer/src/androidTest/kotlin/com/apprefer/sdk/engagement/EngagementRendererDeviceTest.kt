package com.apprefer.sdk.engagement

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

class TapTargetActivity : Activity()

/** The shared renderer on a real Android system: posting, buttons, taps, snooze, dismissal, decoding. */
@RunWith(AndroidJUnit4::class)
class EngagementRendererDeviceTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val nm = context.getSystemService(NotificationManager::class.java)
    private val iid = "ei_" + "a".repeat(26)
    private val labels = mapOf("open" to "Open", "view" to "View", "start" to "Starten", "snooze" to "Später erinnern")
    private val channels = mapOf("marketing" to "Angebote", "reminders" to "Erinnerungen", "service" to "Konto")

    private fun payload(overrides: Map<String, Any?> = emptyMap()): Map<String, String> {
        val o = JSONObject(mapOf(
            "type" to "engagement", "v" to 1, "mid" to "sparkly_live_finish-setup_dev_nudge", "purpose" to "reminders",
            "exp" to System.currentTimeMillis() + 3_600_000, "epoch" to 2, "iid" to iid, "dest" to "/setup",
            "title" to "Finish Garden", "body" to "One step left.", "rt" to "er1." + "A".repeat(43),
        ))
        o.put("actions", JSONArray(listOf(JSONObject(mapOf("id" to "start", "kind" to "start", "dest" to "/setup/next")), JSONObject(mapOf("id" to "later", "kind" to "snooze", "min" to 60)))))
        for ((k, v) in overrides) if (v == null) o.remove(k) else o.put(k, v)
        return mapOf("type" to "engagement", "v" to "1", "engagement" to o.toString())
    }

    private fun ours(): List<android.service.notification.StatusBarNotification> = nm.activeNotifications.filter { it.tag?.startsWith("apprefer.eng") == true }

    @Before fun setUp() {
        if (Build.VERSION.SDK_INT >= 33) {
            InstrumentationRegistry.getInstrumentation().uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
        }
        nm.cancelAll()
        context.getSharedPreferences(EngagementRenderer.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        EngagementRenderer.configure(context, android.R.drawable.ic_dialog_info, setOf("assets.example.com"), labels, channels, null)
        EngagementRenderer.setIdentity(context, iid, 2)
    }
    @After fun tearDown() { nm.cancelAll() }

    private fun waitFor(condition: () -> Boolean): Boolean {
        repeat(40) { if (condition()) return true; Thread.sleep(100) }
        return condition()
    }

    @Test fun postsOnThePurposeChannelWithButtonsAndExpiry() {
        assertEquals("granted", EngagementRenderer.permission(context))
        assertEquals(EngagementRenderer.Result.SHOWN_TEXT_ONLY, EngagementRenderer.handleMessage(context, payload(), System.currentTimeMillis(), 2000, 300_000))
        assertTrue(waitFor { ours().size == 1 })
        val n = ours().single().notification
        assertEquals("apprefer_reminders", n.channelId)
        assertEquals("Finish Garden", n.extras.getString(Notification.EXTRA_TITLE))
        assertEquals(listOf("Starten", "Später erinnern"), n.actions.map { it.title.toString() })
        assertNotNull(n.contentIntent); assertNotNull(n.deleteIntent)
        assertTrue(n.timeoutAfter in 1..3_600_000)
        assertEquals("Erinnerungen", nm.getNotificationChannel("apprefer_reminders").name.toString())
        assertEquals(true, EngagementRenderer.channelEnabled(context, "reminders"))
        assertEquals(listOf("received"), EngagementRenderer.takeEvents(context).map { it.getString("kind") })
    }

    @Test fun otherAccountsInstallsExpiredAndSupportMessagesAreNotShown() {
        EngagementRenderer.setIdentity(context, iid, 3)
        assertEquals(EngagementRenderer.Result.IGNORED, EngagementRenderer.handleMessage(context, payload(), System.currentTimeMillis(), 2000, 300_000))
        EngagementRenderer.setIdentity(context, iid, -1) // sign-out in progress
        assertEquals(EngagementRenderer.Result.IGNORED, EngagementRenderer.handleMessage(context, payload(), System.currentTimeMillis(), 2000, 300_000))
        EngagementRenderer.setIdentity(context, iid, 2)
        assertEquals(EngagementRenderer.Result.IGNORED, EngagementRenderer.handleMessage(context, payload(mapOf("iid" to "ei_" + "b".repeat(26))), System.currentTimeMillis(), 2000, 300_000))
        assertEquals(EngagementRenderer.Result.IGNORED, EngagementRenderer.handleMessage(context, payload(mapOf("exp" to System.currentTimeMillis() - 1)), System.currentTimeMillis(), 2000, 300_000))
        assertEquals(EngagementRenderer.Result.IGNORED, EngagementRenderer.handleMessage(context, payload(mapOf("v" to 9)), System.currentTimeMillis(), 2000, 300_000))
        assertEquals(EngagementRenderer.Result.NOT_ENGAGEMENT, EngagementRenderer.handleMessage(context, mapOf("type" to "support_reply", "conversationId" to "cv_1"), System.currentTimeMillis(), 2000, 300_000))
        Thread.sleep(300)
        assertTrue(ours().isEmpty())
        assertTrue(EngagementRenderer.takeEvents(context).isEmpty())
    }

    @Test fun anUnreachableImageFallsBackToCompleteText() {
        // Allowlisted host that doesn't resolve here: the text still goes out, within the time limit.
        val start = System.currentTimeMillis()
        assertEquals(EngagementRenderer.Result.SHOWN_TEXT_ONLY, EngagementRenderer.handleMessage(context,
            payload(mapOf("img" to "https://assets.example.com/push/v1/hero.png")), System.currentTimeMillis(), 1500, 300_000))
        assertTrue(System.currentTimeMillis() - start < 5000)
        assertTrue(waitFor { ours().size == 1 })
        assertEquals("One step left.", ours().single().notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }

    @Test fun tapsAreAcceptedOnlyFromRecordedNotificationsAndOnce() {
        EngagementRenderer.handleMessage(context, payload(), System.currentTimeMillis(), 2000, 300_000)
        EngagementRenderer.takeEvents(context)
        val nonce = EngagementRenderer.recordNonces(context).single()
        val forged = Intent(context, TapTargetActivity::class.java).putExtra(EngagementRenderer.EXTRA_NONCE, "f".repeat(32))
        assertFalse(EngagementRenderer.handleIntent(context, forged, System.currentTimeMillis()))
        assertFalse(EngagementRenderer.handleIntent(context, Intent(context, TapTargetActivity::class.java).putExtra(EngagementRenderer.EXTRA_NONCE, "../x"), System.currentTimeMillis()))
        val tap = Intent(context, TapTargetActivity::class.java).putExtra(EngagementRenderer.EXTRA_NONCE, nonce)
        assertTrue(EngagementRenderer.handleIntent(context, tap, System.currentTimeMillis()))
        assertFalse("extras removed, so a recreated activity doesn't report it again", tap.hasExtra(EngagementRenderer.EXTRA_NONCE))
        assertTrue(EngagementRenderer.handleIntent(context, Intent().putExtra(EngagementRenderer.EXTRA_NONCE, nonce), System.currentTimeMillis()))
        val button = Intent().putExtra(EngagementRenderer.EXTRA_NONCE, nonce).putExtra(EngagementRenderer.EXTRA_ACTION, "start")
        assertTrue(EngagementRenderer.handleIntent(context, button, System.currentTimeMillis()))
        val snoozeAsActivity = Intent().putExtra(EngagementRenderer.EXTRA_NONCE, nonce).putExtra(EngagementRenderer.EXTRA_ACTION, "later")
        assertTrue(EngagementRenderer.handleIntent(context, snoozeAsActivity, System.currentTimeMillis()))
        val events = EngagementRenderer.takeEvents(context)
        assertEquals("one open (despite two taps) and one button", listOf("opened", "action"), events.map { it.getString("kind") })
        assertEquals("/setup", events[0].getString("destination"))
        assertEquals("/setup/next", events[1].getString("destination"))
        assertEquals("start", events[1].getString("actionId"))
    }

    @Test fun theContentIntentIsExplicitAndImmutable() {
        EngagementRenderer.handleMessage(context, payload(), System.currentTimeMillis(), 2000, 300_000)
        assertTrue(waitFor { ours().size == 1 })
        val n = ours().single().notification
        assertEquals(context.packageName, n.contentIntent.creatorPackage)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(n.contentIntent.isImmutable)
        assertTrue(n.contentIntent.isActivity)
        assertTrue(n.actions[1].actionIntent.isBroadcast)
        if (Build.VERSION.SDK_INT >= 31) assertTrue(n.actions[1].actionIntent.isImmutable)
    }

    @Test fun snoozeHidesOnceAndReShowsWithoutTheSnoozeButton() {
        EngagementRenderer.handleMessage(context, payload(), System.currentTimeMillis(), 2000, 300_000)
        assertTrue(waitFor { ours().size == 1 })
        EngagementRenderer.takeEvents(context)
        val nonce = EngagementRenderer.recordNonces(context).single()
        val snooze = Intent(EngagementRenderer.ACTION_SNOOZE).putExtra(EngagementRenderer.EXTRA_NONCE, nonce).putExtra(EngagementRenderer.EXTRA_ACTION, "later")
        EngagementRenderer.onReceive(context, snooze, System.currentTimeMillis())
        assertTrue(waitFor { ours().isEmpty() })
        EngagementRenderer.onReceive(context, snooze, System.currentTimeMillis())
        assertEquals(listOf("snoozed"), EngagementRenderer.takeEvents(context).map { it.getString("kind") })
        // The alarm fires (simulated): shown again, its only button "Starten".
        EngagementRenderer.onReceive(context, Intent(EngagementRenderer.ACTION_SHOW).putExtra(EngagementRenderer.EXTRA_NONCE, nonce), System.currentTimeMillis())
        assertTrue(waitFor { ours().size == 1 })
        assertEquals(listOf("Starten"), ours().single().notification.actions.map { it.title.toString() })
        // After a sign-out it is not shown again.
        nm.cancelAll()
        EngagementRenderer.setIdentity(context, iid, 3)
        EngagementRenderer.onReceive(context, Intent(EngagementRenderer.ACTION_SHOW).putExtra(EngagementRenderer.EXTRA_NONCE, nonce), System.currentTimeMillis())
        Thread.sleep(300)
        assertTrue(ours().isEmpty())
    }

    @Test fun dismissalIsRecordedOnceAndAccountChangesClearWhatWaits() {
        EngagementRenderer.handleMessage(context, payload(), System.currentTimeMillis(), 2000, 300_000)
        val nonce = EngagementRenderer.recordNonces(context).single()
        val dismiss = Intent(EngagementRenderer.ACTION_DISMISS).putExtra(EngagementRenderer.EXTRA_NONCE, nonce)
        EngagementRenderer.onReceive(context, dismiss, System.currentTimeMillis())
        EngagementRenderer.onReceive(context, dismiss, System.currentTimeMillis())
        assertEquals(listOf("received", "dismissed"), EngagementRenderer.takeEvents(context).map { it.getString("kind") })
        EngagementRenderer.clearForAccountChange(context)
        assertTrue(EngagementRenderer.recordNonces(context).isEmpty())
        assertFalse(EngagementRenderer.handleIntent(context, Intent().putExtra(EngagementRenderer.EXTRA_NONCE, nonce), System.currentTimeMillis()))
    }

    @Test fun decodeAcceptsRealImagesWithinThePixelLimit() {
        fun png(w: Int, h: Int) = ByteArrayOutputStream().also { Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
        val ok = EngagementRenderer.decode(png(1200, 600), 2048)
        assertNotNull(ok); assertEquals(1200, ok!!.width)
        val big = EngagementRenderer.decode(png(2600, 600), 2048)
        assertNull("over the pixel limit", big)
        assertNull("not an image", EngagementRenderer.decode(ByteArray(64) { 7 }, 2048))
        val sampled = EngagementRenderer.decode(png(2048, 2048), 2048)!!
        assertTrue("decoded down for notifications", sampled.width <= 1024)
    }
}

/** Without the notification permission nothing is posted or reported as received. */
@RunWith(AndroidJUnit4::class)
class EngagementRendererPermissionTest {
    @Test fun deniedPermissionIsNotReportedAsReceived() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        EngagementRenderer.permissionForTesting = "denied"
        try {
            context.getSharedPreferences(EngagementRenderer.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
            EngagementRenderer.configure(context, android.R.drawable.ic_dialog_info, emptySet(), mapOf("start" to "Start"), emptyMap(), null)
            EngagementRenderer.setIdentity(context, "ei_" + "a".repeat(26), 2)
            assertEquals("denied", EngagementRenderer.permission(context))
            val o = JSONObject(mapOf("type" to "engagement", "v" to 1, "mid" to "sparkly_live_j_perm_nudge", "purpose" to "reminders",
                "exp" to System.currentTimeMillis() + 3_600_000, "epoch" to 2, "iid" to "ei_" + "a".repeat(26), "title" to "T", "body" to "B", "rt" to "er1." + "A".repeat(43)))
            val data = mapOf("type" to "engagement", "v" to "1", "engagement" to o.toString())
            assertEquals(EngagementRenderer.Result.IGNORED, EngagementRenderer.handleMessage(context, data, System.currentTimeMillis(), 1000, 300_000))
            assertTrue(EngagementRenderer.takeEvents(context).isEmpty())
        } finally {
            EngagementRenderer.permissionForTesting = null
        }
    }
}
