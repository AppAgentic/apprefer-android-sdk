package com.apprefer.sdk.support

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.apprefer.sdk.AppReferContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.text.NumberFormat

/**
 * The Support strings through real Android resources: every language's folder (iw, in, b+zh+Hant,
 * es-rMX, …) resolves to its own table, and plurals use the platform's rules. Run
 * connectedDebugAndroidTest on an emulator.
 */
@RunWith(AndroidJUnit4::class)
class SupportLocalizationDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val table: JSONObject by lazy {
        JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("support-strings.json").bufferedReader().use { it.readText() })
    }

    @Before fun setUp() {
        AppReferContext.context = context.applicationContext
        SupportStrings.testSource = null; SupportStrings.testLanguages = null; SupportStrings.overrides = emptyMap()
    }
    @After fun tearDown() { SupportStrings.forcedLanguage = null }

    private fun source(language: String): JSONObject? = if (language == "en") null else table.getJSONObject("translations").getJSONObject(language)

    @Test fun everyLanguageReadsItsOwnResources() {
        val keys = table.getJSONObject("keys")
        for (language in SupportStrings.supportedLanguages) {
            SupportStrings.forcedLanguage = language
            assertEquals(language, SupportStrings.language)
            for (key in keys.keys()) {
                val value = source(language)?.get(key) ?: keys.getJSONObject(key).get("en")
                if (value is String) assertEquals("$language $key", value, SupportStrings.text(key))
            }
        }
    }

    @Test fun pluralsUseThePlatformRules() {
        val counts = (0..30) + (100..112) + listOf(1_000_000)
        for (language in SupportStrings.supportedLanguages) {
            SupportStrings.forcedLanguage = language
            val forms = (source(language)?.get("support.a11y.unread") ?: table.getJSONObject("keys").getJSONObject("support.a11y.unread").get("en")) as JSONObject
            val rules = android.icu.text.PluralRules.forLocale(SupportStrings.localeOf(language))
            for (n in counts) {
                val category = rules.select(n.toDouble())
                val form = if (forms.has(category)) forms.getString(category) else forms.getString("other")
                val expected = form.replace("{count}", NumberFormat.getIntegerInstance(SupportStrings.localeOf(language)).format(n.toLong()))
                assertEquals("$language $n ($category)", expected, SupportStrings.plural("support.a11y.unread", n))
            }
        }
    }

    @Test fun arabicAndHebrewLayOutRightToLeft() {
        for ((language, rtl) in listOf("ar" to true, "he" to true, "de" to false)) {
            SupportStrings.forcedLanguage = language
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val view = AppReferSupportView(context)
                assertEquals(language, if (rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, view.layoutDirection)
            }
        }
    }
}
