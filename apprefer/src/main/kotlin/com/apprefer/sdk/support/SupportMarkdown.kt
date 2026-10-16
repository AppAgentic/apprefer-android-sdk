package com.apprefer.sdk.support

/** Inline styles in a help article's text. */
internal enum class SupportInlineStyle { BOLD, ITALIC, CODE, STRIKE, LINK }

/** A styled range of [SupportRichText.text]; [url] is set for links (always http(s) or mailto). */
internal data class SupportSpan(val start: Int, val end: Int, val style: SupportInlineStyle, val url: String? = null)

/** Plain text plus style ranges. Pure Kotlin, so it runs in JVM unit tests; the view turns it into spans. */
internal data class SupportRichText(val text: String, val spans: List<SupportSpan> = emptyList()) {
    val links: List<String> get() = spans.mapNotNull { it.url }

    operator fun plus(other: SupportRichText): SupportRichText =
        SupportRichText(text + other.text, spans + other.spans.map { it.copy(start = it.start + text.length, end = it.end + text.length) })
}

/** A block of a help article. */
internal sealed class SupportMarkdownBlock {
    data class Heading(val level: Int, val text: SupportRichText) : SupportMarkdownBlock()
    data class Paragraph(val text: SupportRichText) : SupportMarkdownBlock()
    /** One list item; [depth] 0 is the outer list. [number] is set for ordered lists. */
    data class ListItem(val number: Int?, val depth: Int, val text: SupportRichText) : SupportMarkdownBlock()
    data class Quote(val text: SupportRichText) : SupportMarkdownBlock()
    data class Code(val code: String) : SupportMarkdownBlock()
    object Rule : SupportMarkdownBlock() { override fun toString() = "Rule" }
    /** A picture on its own line (https only is loaded). */
    data class Image(val url: String, val alt: String) : SupportMarkdownBlock()
}

/**
 * A small, safe markdown reader for help articles (same rules as the iOS SDK): headings, lists,
 * quotes, fenced code, rules, images, and inline bold, italic, code, strikethrough and links.
 * Nothing is ever executed: HTML tags are dropped (script and style with their content), and links
 * keep only http(s) and mailto targets; everything else (javascript:, data:, file:, relative paths,
 * app schemes) becomes plain text.
 */
internal object SupportMarkdown {
    fun blocks(markdown: String): List<SupportMarkdownBlock> {
        val lines = markdown.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<SupportMarkdownBlock>()
        val paragraph = mutableListOf<String>()
        val quote = mutableListOf<String>()
        var listOpen = false
        var index = 0

        fun flushParagraph() {
            if (paragraph.isEmpty()) return
            blocks.add(SupportMarkdownBlock.Paragraph(inline(joinLines(paragraph))))
            paragraph.clear()
        }
        fun flushQuote() {
            if (quote.isEmpty()) return
            blocks.add(SupportMarkdownBlock.Quote(inline(joinLines(quote))))
            quote.clear()
        }
        fun flushAll() { flushParagraph(); flushQuote(); listOpen = false }

        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim(' ', '\t')
            index++

            // Fenced code: everything up to the closing fence, as written.
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                flushAll()
                val fence = trimmed.substring(0, 3)
                val code = mutableListOf<String>()
                while (index < lines.size && !lines[index].trim(' ', '\t').startsWith(fence)) { code.add(lines[index]); index++ }
                index++ // the closing fence
                blocks.add(SupportMarkdownBlock.Code(code.joinToString("\n")))
                continue
            }
            if (trimmed.isEmpty()) { flushAll(); continue }

            // Setext headings: a paragraph line underlined with === or ---.
            if (paragraph.isNotEmpty() && trimmed.length >= 2 && (trimmed.all { it == '=' } || trimmed.all { it == '-' })) {
                val text = joinLines(paragraph); paragraph.clear()
                blocks.add(SupportMarkdownBlock.Heading(if (trimmed[0] == '=') 1 else 2, inline(text)))
                continue
            }
            if (isRule(trimmed)) { flushAll(); blocks.add(SupportMarkdownBlock.Rule); continue }

            val heading = atxHeading(trimmed)
            if (heading != null) {
                flushAll()
                blocks.add(SupportMarkdownBlock.Heading(heading.first, inline(heading.second)))
                continue
            }
            if (trimmed.startsWith(">")) {
                flushParagraph(); listOpen = false
                quote.add(trimmed.substring(1).trim(' ', '\t'))
                continue
            }
            flushQuote()
            val item = listItem(line)
            if (item != null) {
                flushParagraph()
                listOpen = true
                blocks.add(SupportMarkdownBlock.ListItem(item.first, item.second, inline(item.third)))
                continue
            }
            // An indented line right after a list item continues that item.
            val last = blocks.lastOrNull()
            if (listOpen && (line.startsWith(" ") || line.startsWith("\t")) && last is SupportMarkdownBlock.ListItem) {
                blocks[blocks.size - 1] = last.copy(text = last.text + SupportRichText(" ") + inline(trimmed))
                continue
            }
            listOpen = false
            if (paragraph.isEmpty()) {
                val image = standaloneImage(trimmed)
                if (image != null) {
                    if (!(image is SupportMarkdownBlock.Paragraph && image.text.text.isEmpty())) blocks.add(image)
                    continue
                }
            }
            paragraph.add(line)
        }
        flushAll()
        return blocks
    }

    // MARK: - Inline

    /** Inline markdown with only safe links kept and HTML tags removed. */
    fun inline(text: String): SupportRichText {
        val out = StringBuilder()
        val spans = mutableListOf<SupportSpan>()
        parseInline(stripHTML(text), out, spans, 0)
        return SupportRichText(out.toString(), spans.sortedWith(compareBy({ it.start }, { -it.end })))
    }

    /** The URL when it is an http(s) link with a host, or a mailto link; null otherwise. */
    fun safeUrl(string: String?): String? {
        val trimmed = string?.trim() ?: return null
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() || it.code < 0x20 }) return null
        val uri = runCatching { java.net.URI(trimmed) }.getOrNull() ?: return null
        return when (uri.scheme?.lowercase()) {
            "http", "https" -> trimmed.takeIf { !uri.isOpaque && !uri.host.isNullOrEmpty() }
            "mailto" -> trimmed.takeIf { it.length > "mailto:".length }
            else -> null
        }
    }

    /** Removes HTML tags (and script/style elements with their content) outside `code` spans. Autolinks are kept. */
    fun stripHTML(text: String): String {
        if (!text.contains('<')) return text
        return text.split('`').mapIndexed { i, part ->
            if (i % 2 != 0 || !part.contains('<')) return@mapIndexed part
            var s = part.replace(SCRIPT_CLOSED, "")
            s = s.replace(SCRIPT_OPEN, "")
            s = s.replace(BREAK, "\n")
            s = s.replace(COMMENT, "")
            s.replace(TAG, "")
        }.joinToString("`")
    }

    private val SCRIPT_CLOSED = Regex("<(script|style)\\b[^>]*>[\\s\\S]*?</\\1\\s*>", RegexOption.IGNORE_CASE)
    private val SCRIPT_OPEN = Regex("<(script|style)\\b[^>]*>[\\s\\S]*$", RegexOption.IGNORE_CASE)
    private val BREAK = Regex("<br\\s*/?>", RegexOption.IGNORE_CASE)
    private val COMMENT = Regex("<!--[\\s\\S]*?-->")
    private val TAG = Regex("</?[A-Za-z][A-Za-z0-9-]*(\\s[^<>]*)?/?>")
    private val AUTOLINK = Regex("^[A-Za-z][A-Za-z0-9+.-]{1,31}:[^\\s<>]*$")
    private val EMAIL = Regex("^[^\\s@<>]+@[^\\s@<>]+\\.[^\\s@<>]+$")
    private const val PUNCTUATION = "!\"#$%&'()*+,-./:;<=>?@[\\]^_`{|}~"

    private fun parseInline(s: String, out: StringBuilder, spans: MutableList<SupportSpan>, depth: Int) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '\\' && i + 1 < s.length && s[i + 1] in PUNCTUATION -> { out.append(s[i + 1]); i += 2 }
                c == '`' -> {
                    val run = runLength(s, i, '`')
                    val close = findCodeClose(s, i + run, run)
                    if (close < 0) { out.append(s, i, i + run); i += run }
                    else {
                        var code = s.substring(i + run, close)
                        if (code.length >= 2 && code.startsWith(" ") && code.endsWith(" ") && code.isNotBlank()) code = code.substring(1, code.length - 1)
                        val start = out.length; out.append(code)
                        spans.add(SupportSpan(start, out.length, SupportInlineStyle.CODE))
                        i = close + run
                    }
                }
                c == '!' && i + 1 < s.length && s[i + 1] == '[' && depth < 8 -> {
                    val link = link(s, i + 1)
                    if (link == null) { out.append(c); i++ }
                    else { parseInline(link.first, out, spans, depth + 1); i = link.third } // an inline picture shows its alt text
                }
                c == '[' && depth < 8 -> {
                    val link = link(s, i)
                    if (link == null) { out.append(c); i++ }
                    else {
                        val start = out.length
                        parseInline(link.first, out, spans, depth + 1)
                        safeUrl(link.second)?.let { if (out.length > start) spans.add(SupportSpan(start, out.length, SupportInlineStyle.LINK, it)) }
                        i = link.third
                    }
                }
                c == '<' -> {
                    val close = s.indexOf('>', i + 1)
                    val inner = if (close > i) s.substring(i + 1, close) else ""
                    val target = when {
                        AUTOLINK.matches(inner) -> inner
                        EMAIL.matches(inner) -> "mailto:$inner"
                        else -> null
                    }
                    if (target == null) { out.append(c); i++ }
                    else {
                        val start = out.length; out.append(inner)
                        safeUrl(target)?.let { spans.add(SupportSpan(start, out.length, SupportInlineStyle.LINK, it)) }
                        i = close + 1
                    }
                }
                (c == '*' || c == '_' || c == '~') && depth < 8 -> {
                    val run = runLength(s, i, c)
                    val width = when {
                        c == '~' -> if (run >= 2) 2 else 0
                        run >= 2 -> 2
                        else -> 1
                    }
                    val close = if (width == 0) -1 else findDelimiterClose(s, i, c, width)
                    if (close < 0) { out.append(s, i, i + run); i += run }
                    else {
                        val start = out.length
                        parseInline(s.substring(i + width, close), out, spans, depth + 1)
                        val style = when { c == '~' -> SupportInlineStyle.STRIKE; width == 2 -> SupportInlineStyle.BOLD; else -> SupportInlineStyle.ITALIC }
                        if (out.length > start) spans.add(SupportSpan(start, out.length, style))
                        i = close + width
                    }
                }
                else -> { out.append(c); i++ }
            }
        }
    }

    private fun runLength(s: String, from: Int, c: Char): Int { var n = 0; while (from + n < s.length && s[from + n] == c) n++; return n }

    /** The start of the next run of exactly [run] backticks, or -1. */
    private fun findCodeClose(s: String, from: Int, run: Int): Int {
        var j = from
        while (j < s.length) {
            if (s[j] == '`') {
                val n = runLength(s, j, '`')
                if (n == run) return j
                j += n
            } else j++
        }
        return -1
    }

    /**
     * The closing delimiter for an opening run of [width] × [c] at [open], or -1. The opening must be
     * followed by a non-space and the closing preceded by one; `_` only opens and closes at word edges.
     */
    private fun findDelimiterClose(s: String, open: Int, c: Char, width: Int): Int {
        val first = open + width
        if (first >= s.length || s[first].isWhitespace()) return -1
        if (c == '_' && open > 0 && s[open - 1].isLetterOrDigit()) return -1
        var j = first
        while (j < s.length) {
            val ch = s[j]
            when {
                ch == '\\' -> j += 2
                ch == '`' -> { val n = runLength(s, j, '`'); val close = findCodeClose(s, j + n, n); j = if (close < 0) j + n else close + n }
                ch == c -> {
                    val n = runLength(s, j, c)
                    val fits = if (width == 1) n == 1 || n >= 3 else n >= 2
                    val after = j + width
                    val wordEdge = c != '_' || after >= s.length || !s[after].isLetterOrDigit()
                    if (fits && j > first && !s[j - 1].isWhitespace() && wordEdge) return j
                    j += n
                }
                else -> j++
            }
        }
        return -1
    }

    /** `[label](destination "title")` starting at [open] ('['): label, destination and the index after it. */
    private fun link(s: String, open: Int): Triple<String, String, Int>? {
        var depth = 0
        var j = open
        var labelEnd = -1
        while (j < s.length) {
            when (s[j]) {
                '\\' -> j++
                '[' -> depth++
                ']' -> { depth--; if (depth == 0) { labelEnd = j; break } }
            }
            j++
        }
        if (labelEnd < 0 || labelEnd + 1 >= s.length || s[labelEnd + 1] != '(') return null
        var parens = 0
        var k = labelEnd + 1
        var destEnd = -1
        while (k < s.length) {
            when (s[k]) {
                '\\' -> k++
                '(' -> parens++
                ')' -> { parens--; if (parens == 0) { destEnd = k; break } }
            }
            k++
        }
        if (destEnd < 0) return null
        var destination = s.substring(labelEnd + 2, destEnd).trim()
        if (destination.startsWith("<")) {
            val close = destination.indexOf('>')
            destination = if (close > 0) destination.substring(1, close) else destination.substring(1)
        } else {
            val space = destination.indexOfFirst { it.isWhitespace() }
            if (space >= 0) destination = destination.substring(0, space)
        }
        return Triple(s.substring(open + 1, labelEnd), destination, destEnd + 1)
    }

    // MARK: - Block helpers

    /** Lines of one paragraph: a soft break is a space; two trailing spaces or a backslash make a line break. */
    private fun joinLines(lines: List<String>): String {
        val out = StringBuilder()
        lines.forEachIndexed { i, raw ->
            val hard = raw.endsWith("  ") || raw.endsWith("\\")
            var line = raw.trim(' ', '\t')
            if (line.endsWith("\\")) line = line.dropLast(1)
            out.append(line)
            if (i < lines.size - 1) out.append(if (hard) "\n" else " ")
        }
        return out.toString()
    }

    private fun isRule(s: String): Boolean {
        val compact = s.replace(" ", "")
        if (compact.length < 3 || compact[0] !in "-*_") return false
        return compact.all { it == compact[0] }
    }

    private fun atxHeading(s: String): Pair<Int, String>? {
        val hashes = s.takeWhile { it == '#' }.length
        if (hashes !in 1..6) return null
        val rest = s.substring(hashes)
        if (rest.isNotEmpty() && rest[0] != ' ' && rest[0] != '\t') return null
        var text = rest.trim(' ', '\t')
        // Closing hashes ("## Title ##").
        text = text.trimEnd('#')
        return hashes to text.trim(' ', '\t')
    }

    private fun listItem(line: String): Triple<Int?, Int, String>? {
        val expanded = line.replace("\t", "    ")
        val indent = expanded.takeWhile { it == ' ' }.length
        val rest = expanded.substring(indent)
        val depth = minOf(indent / 2, 3)
        if (rest.length >= 2 && rest[0] in "-*+" && rest[1] == ' ') {
            val text = rest.substring(2).trim(' ', '\t')
            // "- [ ] task" / "- [x] task": shown as a plain item with a box.
            if (text.startsWith("[ ] ")) return Triple(null, depth, "☐ " + text.substring(4))
            if (text.lowercase().startsWith("[x] ")) return Triple(null, depth, "☑︎ " + text.substring(4))
            return Triple(null, depth, text)
        }
        val digits = rest.takeWhile { it in '0'..'9' }
        if (digits.isEmpty() || digits.length > 9) return null
        val after = rest.substring(digits.length)
        if (after.length < 2 || (after[0] != '.' && after[0] != ')') || after[1] != ' ') return null
        return Triple(digits.toInt(), depth, after.substring(2).trim(' ', '\t'))
    }

    /** `![alt](url "title")` alone on a line; an unsafe one becomes its alt text. */
    private fun standaloneImage(s: String): SupportMarkdownBlock? {
        if (!s.startsWith("![") || !s.endsWith(")")) return null
        val close = s.indexOf("](")
        if (close < 0) return null
        val alt = s.substring(2, close)
        var target = s.substring(close + 2, s.length - 1).trim()
        val space = target.indexOf(' ')
        if (space >= 0) target = target.substring(0, space)
        target = target.trim('<', '>')
        val url = safeUrl(target)
        if (url == null || url.lowercase().startsWith("mailto:")) return SupportMarkdownBlock.Paragraph(SupportRichText(alt))
        return SupportMarkdownBlock.Image(url, alt)
    }
}
