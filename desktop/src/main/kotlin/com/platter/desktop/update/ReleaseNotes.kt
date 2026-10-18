package com.platter.desktop.update

/**
 * A release's notes as GitHub keeps them - Markdown - read into what the update notice shows: a list of what changed,
 * with any headings and plain lines kept as they are.
 *
 * Written for notes like
 * ```
 * - Добавлен кроссфейд
 * - Исправлен поиск
 * ```
 * and forgiving of the rest: `*`, `+`, `•` and numbered items count as items too, bold and links are shown as plain
 * words, and what GitHub adds to notes it writes itself (a "What's Changed" heading, "by @someone in <link>", the "Full
 * Changelog" line) is left out, since the notice has its own heading and nobody follows those links from it.
 * The phone has the same reading in `update/ReleaseNotes.kt`.
 */
object ReleaseNotes {
    sealed interface Block {
        val text: String
    }

    data class Item(override val text: String) : Block
    data class Heading(override val text: String) : Block
    data class Line(override val text: String) : Block

    fun parse(markdown: String): List<Block> {
        val blocks = mutableListOf<Block>()
        for (raw in markdown.replace("\r", "").lines()) {
            if (raw.isBlank() || FULL_CHANGELOG.containsMatchIn(raw) || RULE.matches(raw.trim())) continue

            val heading = HEADING.matchEntire(raw.trim())
            if (heading != null) {
                val text = clean(heading.groupValues[1])
                if (text.isNotEmpty() && text.trimEnd(':').lowercase() !in OWN_TITLES) blocks += Heading(text)
                continue
            }

            val item = ITEM.matchEntire(raw)
            if (item != null) {
                clean(item.groupValues[1]).takeIf { it.isNotEmpty() }?.let { blocks += Item(it) }
                continue
            }

            // An indented line under an item goes on with that item, as Markdown wraps it.
            val last = blocks.lastOrNull()
            val text = clean(raw)
            if (last is Item && raw.first().isWhitespace()) {
                blocks[blocks.lastIndex] = Item(last.text + " " + text)
            } else if (text.trimEnd(':').lowercase() !in OWN_TITLES) {
                blocks += Line(text)
            }
        }
        return blocks
    }

    private fun clean(text: String): String = text
        .replace(LINK, "$1")
        .replace(GITHUB_CREDIT, "")
        .replace(EMPHASIS, "")
        .trim()

    private val HEADING = Regex("""#{1,6}\s+(.*)""")
    private val ITEM = Regex("""\s*(?:[-*+•]|\d+[.)])\s+(.*)""")
    private val LINK = Regex("""\[([^\]]*)]\([^)]*\)""")
    private val GITHUB_CREDIT = Regex("""\s+by @\S+ in https?://\S+$""")
    private val EMPHASIS = Regex("""\*\*|__|`""")
    private val RULE = Regex("""[-*_]{3,}""")
    private val FULL_CHANGELOG = Regex("""^\s*\**Full Changelog\**:""", RegexOption.IGNORE_CASE)

    /** Titles the notice already gives the list, so a release that repeats one does not show it twice. */
    private val OWN_TITLES = setOf("what's changed", "what's new", "changelog", "что изменилось", "что нового", "изменения")
}
