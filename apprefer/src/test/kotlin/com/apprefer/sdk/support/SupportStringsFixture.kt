package com.apprefer.sdk.support

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The generated string resources, read straight from `src/main/res` (JVM unit tests have no
 * Android resources), as a [SupportStrings.Source]. Plural forms are picked with the SDK's CLDR rules.
 */
internal object SupportStringsFixture : SupportStrings.Source {
    class Table(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private val res = File("src/main/res").takeIf { it.isDirectory } ?: File("apprefer/src/main/res")
    private val tables = HashMap<String, Table>()

    /** Resource name -> table key, from the generated id map. */
    private val keysByName: Map<String, String> by lazy {
        (SupportStringIds.text.keys + SupportStringIds.plurals.keys).associateBy { resourceName(it) }
    }

    fun resourceName(key: String): String =
        "apprefer_" + key.replace(Regex("(?<=[a-z0-9])([A-Z])"), "_$1").lowercase().replace('.', '_')

    fun folder(language: String): File? {
        val qualifier = SupportStringIds.qualifiers[language] ?: return null
        return File(res, if (qualifier.isEmpty()) "values" else "values-$qualifier")
    }

    /** One language's table by key (empty when the language isn't shipped). */
    @Synchronized fun table(language: String): Table = tables.getOrPut(language) {
        val file = folder(language)?.let { File(it, "apprefer_support_strings.xml") }
        if (file == null || !file.isFile) return@getOrPut Table(emptyMap(), emptyMap())
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val strings = LinkedHashMap<String, String>()
        val plurals = LinkedHashMap<String, Map<String, String>>()
        val nodes = doc.documentElement.childNodes
        for (i in 0 until nodes.length) {
            val e = nodes.item(i) as? Element ?: continue
            val key = keysByName[e.getAttribute("name")] ?: "?" + e.getAttribute("name")
            when (e.tagName) {
                "string" -> strings[key] = unescape(e.textContent)
                "plurals" -> {
                    val items = e.getElementsByTagName("item")
                    plurals[key] = (0 until items.length).associate {
                        val item = items.item(it) as Element
                        item.getAttribute("quantity") to unescape(item.textContent)
                    }
                }
            }
        }
        Table(strings, plurals)
    }

    /** Android string resource unescaping (what aapt does for the escapes the generator writes). */
    fun unescape(raw: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < raw.length) {
            val ch = raw[i]
            if (ch == '\\' && i + 1 < raw.length) {
                when (val next = raw[i + 1]) {
                    'n' -> out.append('\n'); 't' -> out.append('\t')
                    else -> out.append(next)
                }
                i += 2
            } else { out.append(ch); i++ }
        }
        return out.toString()
    }

    override fun text(key: String, language: String): String? = table(language).strings[key]

    override fun plural(key: String, language: String, count: Int): String? {
        val forms = table(language).plurals[key] ?: return null
        return forms[SupportStrings.cldrPluralCategory(count, language)] ?: forms["other"]
    }

    /** Installs the fixture with an English device and no app languages. */
    fun install() {
        SupportStrings.testSource = this
        SupportStrings.testLanguages = emptyList<String>() to listOf("en-US")
        SupportStrings.overrides = emptyMap()
        SupportStrings.forcedLanguage = null
    }

    fun uninstall() {
        SupportStrings.testSource = null
        SupportStrings.testLanguages = null
        SupportStrings.overrides = emptyMap()
        SupportStrings.forcedLanguage = null
    }
}
