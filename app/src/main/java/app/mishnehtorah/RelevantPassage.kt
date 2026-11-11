package app.mishnehtorah

import kotlin.math.max

/** A bounded range in the ORIGINAL unmodified halakhah text. */
internal data class PassageMatch(val start: Int, val end: Int, val confidence: Float)

/**
 * Passage-level evidence finder. Original source text is never rewritten.
 * Combines clause overlap, context-specific anchor phrases and the bundled
 * learned Hebrew embeddings. Rejects weak/unrelated matches instead of
 * pretending to know exactly which passage answers a difficult question.
 */
internal object RelevantPassage {
    private val punctuation = Regex("[.?!:;׃\\n]+")
    private val stop = setOf("מה", "איך", "האם", "של", "את", "על", "עם", "אני",
        "צריך", "מותר", "אסור", "יכול", "חייב", "בפני", "הרמבמ", "רמבמ",
        "איפה", "מתי", "למה", "איזה", "איזו", "זאת", "זה", "הוא", "היא",
        "כדי", "דבר", "בזמנ", "אדמ", "היכנ", "בכל", "שאני", "שיש")

    private fun ranges(text: String): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = 0
        val endStops = punctuation.findAll(text).map { it.range.last + 1 }.toList() + text.length
        for (end in endStops) {
            if (end <= start) continue
            if (end - start < 260) {
                if (text.substring(start, end).isNotBlank()) out.add(start until end)
            } else {
                // Long clauses (including text without punctuation) are split
                // on commas/whitespace near a sensible reading-unit boundary.
                var from = start
                while (from < end) {
                    if (end - from <= 235) {
                        if (text.substring(from, end).isNotBlank()) out.add(from until end)
                        break
                    }
                    val target = (from + 190).coerceAtMost(end - 1)
                    var cut = text.lastIndexOfAny(charArrayOf(',', ' ', '،'), target)
                    if (cut < from + 85) cut = target
                    cut += 1
                    if (text.substring(from, cut).isNotBlank()) out.add(from until cut)
                    from = cut
                }
            }
            start = end
        }
        return out
    }

    fun find(text: String, question: String, focus: String = "",
             semantic: SemanticIndex? = null): PassageMatch? {
        if (text.isBlank() || question.isBlank()) return null
        val words = HebrewSearch.normalize(question).split(' ')
            .filter { it.length > 2 && it !in stop }
            .distinct().take(15)
        val focusNormalized = HebrewSearch.normalize(focus)
        if (words.isEmpty() && focusNormalized.isEmpty()) return null
        val pieces = ranges(text)
        if (pieces.isEmpty()) return null
        val texts = pieces.map { text.substring(it.first, it.last + 1) }
        val semScores = semantic?.similarities(question, texts)
        var winner = -1
        var highest = Float.NEGATIVE_INFINITY
        var supported = false
        for (i in pieces.indices) {
            val normalized = HebrewSearch.normalize(texts[i])
            val tokens = normalized.split(' ').filter { it.length >= 3 }.toHashSet()
            var common = 0
            var lexical = 0f
            for (w in words) {
                val exact = w in tokens || tokens.any { it.startsWith(w) && w.length >= 4 }
                val stemmed = !exact && w.length >= 5 && tokens.any {
                    it.length >= 4 && (it.startsWith(w.drop(1)) || w.startsWith(it.drop(1)))
                }
                if (exact) { common++; lexical += 7f + w.length * .35f }
                else if (stemmed) { common++; lexical += 3.6f }
            }
            val anchor = focusNormalized.isNotEmpty() && normalized.contains(focusNormalized)
            val semanticScore = (semScores?.get(i) ?: 0f).coerceIn(-1f, 1f)
            // Model similarity is supporting evidence, not proof of relevance.
            val score = lexical + semanticScore * 6f + (if (anchor) 42f else 0f) -
                (max(0, normalized.length - 190) * .012f)
            if (score > highest) {
                highest = score
                winner = i
                supported = anchor || common > 0 && (lexical >= 5f || semanticScore > .2f)
            }
        }
        if (winner < 0 || !supported) return null
        val range = pieces[winner]
        val lead = HebrewSearch.normalize(texts[winner])
        // The Rambam often opens a law with a very short question or
        // heading, immediately followed by the answer. Highlighting only
        // the heading would be misleading even when the reference is right.
        val heading = (lead.length < 90 && (
            lead.startsWith("כמה ") || lead.startsWith("אי זו ") ||
            lead.startsWith("סדר ") || lead.startsWith("כיצד ") ||
            texts[winner].trimEnd().endsWith(":")
        ))
        val next = pieces.getOrNull(winner + 1)
        val end = if (heading && next != null && next.first <= range.last + 2)
            next.last + 1 else range.last + 1
        return PassageMatch(range.first, end, highest)
    }
}
