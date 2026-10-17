package com.apprefer.sdk.support

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import com.apprefer.sdk.AppReferContext
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.text.NumberFormat
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The help chat's text, in 35 languages.
 *
 * Lookup order for every key: the app's override (`AppRefer.support.strings`), then the
 * bundled string resource for the chat's language, then English. The chat's language follows
 * the host app: the language the app is running in (its per-app language, or the locales its
 * resources are configured with, limited to the languages it declares in `localeConfig` when it
 * declares any), so an app that ships fewer languages than the SDK gets a chat that matches its
 * own screens; then the device's languages; then English. `AppRefer.support.language` forces one.
 *
 * Keys and translations come from the shared table every AppRefer SDK uses
 * (`Localization/support-strings.json`), generated into `res/values-xx/apprefer_support_strings.xml`
 * by `scripts/generate-support-strings.py`. `{name}`-style placeholders are filled in by name, so a
 * translation may reorder them. Plural keys are `<plurals>` resources (the platform picks the
 * form); overrides for them are `key#one`, `key#few`, … (CLDR cardinal categories) or the bare key.
 */
internal object SupportStrings {
    /** Every language the SDK ships, as table codes. */
    val supportedLanguages: List<String> get() = SupportStringIds.languages

    /** Where bundled text comes from: string resources in the app, the XML files in JVM tests. */
    internal interface Source {
        /** The bundled text for [key] in [language], or null. */
        fun text(key: String, language: String): String?
        /** The bundled plural form of [key] for [count] in [language], or null. */
        fun plural(key: String, language: String, count: Int): String?
    }

    @Volatile var overrides: Map<String, String> = emptyMap()
    @Volatile var forcedLanguage: String? = null

    /** Tests: replaces the resource lookup and the system's language lists. */
    @Volatile internal var testSource: Source? = null
    @Volatile internal var testLanguages: Pair<List<String>, List<String>>? = null

    // MARK: - Language

    /** The chat's language (a table code such as "de", "pt-BR" or "zh-Hant"). */
    val language: String
        get() {
            forcedLanguage?.let { return match(listOf(it)) ?: "en" }
            val (app, device) = testLanguages ?: systemLanguages()
            return resolve(app, device)
        }

    /**
     * The language the app runs in comes first, so the chat matches the app's screens; then the
     * device's languages; then English.
     */
    fun resolve(appLanguages: List<String>, deviceLanguages: List<String>): String = match(appLanguages + deviceLanguages) ?: "en"

    /**
     * The languages the app is showing, best first. [preferred] is the app's own locale list (its
     * per-app language, or the locales its resources are configured with). When the app declares
     * its languages ([declared], from `localeConfig`), only those count; if none of the preferred
     * ones is declared the app shows its default resources, taken to be English when it declares
     * English, else its first declared language. With no declaration the app's list is used as is.
     */
    fun appLanguages(preferred: List<String>, declared: List<String>?): List<String> {
        if (declared.isNullOrEmpty()) return preferred
        val shown = preferred.filter { p -> declared.any { sameLanguage(p, it) } }
        if (shown.isNotEmpty()) return shown
        return listOf(declared.firstOrNull { base(it) == "en" } ?: declared.first())
    }

    /**
     * The first preference the SDK has a table for. Regional variants map to the table a speaker
     * expects: Latin American Spanish to es-MX, Portuguese outside Brazil to pt-PT, Chinese by
     * script (Taiwan, Hong Kong and Macau to Traditional), Norwegian to Bokmål.
     */
    fun match(preferences: List<String>): String? {
        for (preference in preferences) {
            val parts = preference.replace('_', '-').split('-').filter { it.isNotEmpty() }
            val first = parts.firstOrNull()?.lowercase(Locale.ROOT) ?: continue
            val rest = parts.drop(1)
            val script = rest.firstOrNull { it.length == 4 }?.lowercase(Locale.ROOT)?.replaceFirstChar { it.titlecase(Locale.ROOT) }
            val region = rest.firstOrNull { it.length == 2 || (it.length == 3 && it.all(Char::isDigit)) }?.uppercase(Locale.ROOT)
            when (first) {
                "zh" -> {
                    val traditional = script == "Hant" || (script == null && region in setOf("TW", "HK", "MO"))
                    return if (traditional) "zh-Hant" else "zh-Hans"
                }
                "pt" -> return if (region == null || region == "BR") "pt-BR" else "pt-PT"
                "es" -> return if (region == null || region in spanishEurope) "es" else "es-MX"
                "nb", "no", "nn" -> return "nb"
                "iw" -> return "he"
                "in" -> return "id"
                else -> if (first in supportedLanguages) return first
            }
        }
        return null
    }

    /** Regions that read Spain's Spanish; every other region (Latin America, the US, "419") gets es-MX. */
    private val spanishEurope = setOf("ES", "GQ", "EA", "IC", "AD")

    private fun base(tag: String): String = when (val l = tag.replace('_', '-').substringBefore('-').lowercase(Locale.ROOT)) {
        "iw" -> "he"; "in" -> "id"; "no", "nn" -> "nb"; else -> l
    }

    /** Two locales the app would show the same resources for (Chinese also by script). */
    private fun sameLanguage(a: String, b: String): Boolean {
        if (base(a) != base(b)) return false
        return base(a) != "zh" || match(listOf(a)) == match(listOf(b))
    }

    val locale: Locale get() = localeOf(language)

    fun localeOf(language: String): Locale = Locale.forLanguageTag(language)

    val isRightToLeft: Boolean get() = isRightToLeft(language)

    fun isRightToLeft(language: String): Boolean = language == "ar" || language == "he"

    /** The app's locale list and the device's, as BCP 47 tags. */
    private fun systemLanguages(): Pair<List<String>, List<String>> {
        val context = AppReferContext.context
        val device = runCatching { tags(Resources.getSystem().configuration) }.getOrNull().orEmpty()
            .ifEmpty { listOf(Locale.getDefault().toLanguageTag()) }
        if (context == null) return emptyList<String>() to device
        val preferred = runCatching { tags(context.resources.configuration) }.getOrNull().orEmpty()
        return appLanguages(preferred, declaredLanguages(context)) to device
    }

    @Suppress("DEPRECATION")
    private fun tags(configuration: Configuration): List<String> =
        if (Build.VERSION.SDK_INT >= 24) configuration.locales.toLanguageTags().split(',').filter { it.isNotEmpty() }
        else listOfNotNull(configuration.locale?.toLanguageTag())

    @Volatile private var declared: List<String>? = null
    @Volatile private var declaredRead = false

    /** The app's languages from its `localeConfig` (Android 13+), read once; null when it declares none. */
    private fun declaredLanguages(context: Context): List<String>? {
        if (declaredRead) return declared
        val value = if (Build.VERSION.SDK_INT >= 33) runCatching {
            val config = android.app.LocaleConfig(context)
            if (config.status == android.app.LocaleConfig.STATUS_SUCCESS) config.supportedLocales?.toLanguageTags()?.split(',')?.filter { it.isNotEmpty() } else null
        }.getOrNull()?.takeIf { it.isNotEmpty() } else null
        declared = value; declaredRead = true
        return value
    }

    // MARK: - Lookup

    /** The text for [key] with `{placeholders}` filled in. */
    fun text(key: String, arguments: Map<String, String>): String {
        val template = overrides[key] ?: bundled(key, language) ?: bundled(key, "en") ?: key
        return fill(template, arguments)
    }

    fun text(key: String, vararg arguments: Pair<String, String>): String = text(key, arguments.toMap())

    /** A plural key: the form for [count] in the chat's language, with `{count}` filled in. */
    fun plural(key: String, count: Int, arguments: Map<String, String> = emptyMap()): String {
        val lang = language
        val custom = overrides
        val all = arguments + ("count" to NumberFormat.getIntegerInstance(localeOf(lang)).format(count.toLong()))
        val template = custom["$key#${pluralCategory(count, lang)}"] ?: custom[key]
            ?: bundledPlural(key, lang, count) ?: bundledPlural(key, "en", count) ?: key
        return fill(template, all)
    }

    /** The bundled translation, or null when the language or key is missing or empty. */
    fun bundled(key: String, language: String): String? = source()?.text(key, language)?.takeIf { it.isNotEmpty() }

    private fun bundledPlural(key: String, language: String, count: Int): String? =
        source()?.plural(key, language, count)?.takeIf { it.isNotEmpty() }

    private fun source(): Source? = testSource ?: AppReferContext.context?.let { resources(it) }

    /** Holds the application context only. */
    @SuppressLint("StaticFieldLeak") @Volatile private var resourceSource: ResourceSource? = null
    private fun resources(context: Context): ResourceSource =
        resourceSource?.takeIf { it.context === context.applicationContext } ?: ResourceSource(context.applicationContext ?: context).also { resourceSource = it }

    /**
     * Reads the generated string resources through a context configured for the language. With an
     * App Bundle, Play installs only the device's languages: a language the device doesn't list
     * (forced, or an in-app choice on Android 12 and older) can be missing, and then shows English.
     */
    @SuppressLint("AppBundleLocaleChanges")
    private class ResourceSource(val context: Context) : Source {
        private val byLanguage = ConcurrentHashMap<String, Resources>()
        private fun resources(language: String): Resources? = byLanguage[language] ?: runCatching {
            val configuration = Configuration(context.resources.configuration)
            configuration.setLocale(Locale.forLanguageTag(language))
            context.createConfigurationContext(configuration).resources
        }.getOrNull()?.also { byLanguage[language] = it }

        override fun text(key: String, language: String): String? {
            val id = SupportStringIds.text[key] ?: return null
            return runCatching { resources(language)?.getString(id) }.getOrNull()
        }

        override fun plural(key: String, language: String, count: Int): String? {
            val id = SupportStringIds.plurals[key] ?: return null
            return runCatching { resources(language)?.getQuantityString(id, count) }.getOrNull()
        }
    }

    /**
     * Replaces `{name}` with `arguments["name"]`; unknown placeholders stay as written. One pass, so
     * a value that itself contains braces is never expanded again.
     */
    fun fill(template: String, arguments: Map<String, String>): String {
        if (arguments.isEmpty() || !template.contains('{')) return template
        val result = StringBuilder()
        var i = 0
        while (i < template.length) {
            val open = template.indexOf('{', i)
            if (open < 0) { result.append(template, i, template.length); break }
            result.append(template, i, open)
            val close = template.indexOf('}', open + 1)
            val value = if (close > open) arguments[template.substring(open + 1, close)] else null
            if (value != null) { result.append(value); i = close + 1 } else { result.append('{'); i = open + 1 }
        }
        return result.toString()
    }

    // MARK: - Plurals

    /**
     * The CLDR cardinal plural category of a whole number in [language]: the platform's rules
     * (ICU, Android 7+), else the SDK's own copy of the CLDR rules for its languages.
     */
    fun pluralCategory(count: Int, language: String): String {
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            android.icu.text.PluralRules.forLocale(localeOf(language)).select(count.toDouble())
        }.getOrNull()?.let { return it }
        return cldrPluralCategory(count, language)
    }

    /** CLDR cardinal rules for whole numbers in the SDK's languages (same as the iOS SDK). */
    fun cldrPluralCategory(count: Int, language: String): String {
        val n = kotlin.math.abs(count)
        val n10 = n % 10
        val n100 = n % 100
        return when (language.substringBefore('-')) {
            "ja", "ko", "zh", "th", "vi", "id", "ms" -> "other"
            "hi" -> if (n <= 1) "one" else "other"
            "fr" -> if (n <= 1) "one" else if (n % 1_000_000 == 0) "many" else "other"
            "pt" -> if (language != "pt-PT") {
                if (n <= 1) "one" else if (n % 1_000_000 == 0) "many" else "other"
            } else {
                if (n == 1) "one" else if (n != 0 && n % 1_000_000 == 0) "many" else "other"
            }
            "es", "it", "ca" -> if (n == 1) "one" else if (n != 0 && n % 1_000_000 == 0) "many" else "other"
            "ru", "uk" -> when {
                n10 == 1 && n100 != 11 -> "one"
                n10 in 2..4 && n100 !in 12..14 -> "few"
                else -> "many"
            }
            "pl" -> when {
                n == 1 -> "one"
                n10 in 2..4 && n100 !in 12..14 -> "few"
                else -> "many"
            }
            "cs", "sk" -> if (n == 1) "one" else if (n in 2..4) "few" else "other"
            "hr" -> when {
                n10 == 1 && n100 != 11 -> "one"
                n10 in 2..4 && n100 !in 12..14 -> "few"
                else -> "other"
            }
            "ro" -> if (n == 1) "one" else if (n == 0 || n100 in 1..19) "few" else "other"
            "ar" -> when {
                n == 0 -> "zero"
                n == 1 -> "one"
                n == 2 -> "two"
                n100 in 3..10 -> "few"
                n100 in 11..99 -> "many"
                else -> "other"
            }
            "he" -> if (n == 1) "one" else if (n == 2) "two" else "other"
            else -> if (n == 1) "one" else "other" // en, de, nl, sv, da, nb, fi, el, hu, tr
        }
    }

    // MARK: - Errors

    /**
     * What to show for a failed request: the customer's language, never a raw server message.
     * [fallback] is the key for failures with no more specific text (e.g. "support.error.notSent").
     */
    fun errorText(error: Throwable, fallback: String = "support.error.generic"): String {
        if (error is AppReferSupportException) {
            return when (error.reason) {
                AppReferSupportException.Reason.UPLOAD -> text("support.error.upload")
                AppReferSupportException.Reason.UNAVAILABLE -> text("support.error.unavailable")
                AppReferSupportException.Reason.SERVER -> when (error.status) {
                    413 -> text("support.error.tooLong")
                    429 -> text("support.error.rateLimited")
                    else -> text(fallback)
                }
            }
        }
        var cause: Throwable? = error
        repeat(4) {
            when (cause) {
                is UnknownHostException, is ConnectException, is SocketTimeoutException, is NoRouteToHostException,
                is SocketException, is javax.net.ssl.SSLException -> return text("support.error.offline")
            }
            cause = cause?.cause
        }
        return text(fallback)
    }
}
