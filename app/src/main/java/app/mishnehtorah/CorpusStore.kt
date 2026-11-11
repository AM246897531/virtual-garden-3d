package app.mishnehtorah

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Normalizer

data class Law(
    val id: Int,
    val book: String,
    val section: String,
    val chapter: Int,
    val number: Int,
    val text: String
) {
    val reference: String
        get() = book + " · " + section + " · פרק " + chapter + " · הלכה " + number
}

object HebrewSearch {
    private val marks = Regex("[\\u0591-\\u05c7]")
    private val separators = Regex("[^\\p{L}\\p{N}]+")
    private val aliases = mapOf(
        "יעלה ויבוא" to listOf("יעלה ויבא", "ראשי חדשים", "שכח", "לא הזכיר", "מאורע"),
        "ברכת המזון" to listOf("ברכות", "מזון"),
        "שכחתי" to listOf("שכח", "לא הזכיר"),
        "לחזור" to listOf("חוזר", "חוזר ומברך"),
        "מותר" to listOf("מותר", "רשאי"),
        "אסור" to listOf("אסור", "אינו רשאי")
    )
    fun normalize(query: String): String {
        var t = Normalizer.normalize(query, Normalizer.Form.NFKC)
        t = marks.replace(t, "")
        t = Regex("[\\u05BE\\u05C0\\u05C3\\u05F3\\u05F4]").replace(t, " ")
        t = t.replace('ך', 'כ').replace('ם', 'מ').replace('ן', 'נ')
            .replace('ף', 'פ').replace('ץ', 'צ')
        return separators.replace(t, " ").trim().lowercase().replace(Regex("\\s+"), " ")
    }
    fun alternatives(query: String): List<String> {
        val result = mutableListOf(query)
        for ((k, items) in aliases) {
            if (query.contains(k)) result.addAll(items)
        }
        return result.distinct()
    }
}

class CorpusStore private constructor(private val db: SQLiteDatabase) {
    companion object {
        suspend fun open(context: Context): CorpusStore = withContext(Dispatchers.IO) {
            val destination = File(context.filesDir, "rambam.sqlite")
            if (!destination.exists() || destination.length() == 0L) {
                val temporary = File(context.filesDir, "rambam.sqlite.part")
                context.assets.open("rambam.sqlite").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.renameTo(destination)) { "Unable to move corpus database" }
            }
            CorpusStore(SQLiteDatabase.openDatabase(destination.absolutePath, null, SQLiteDatabase.OPEN_READONLY))
        }
    }
    private fun laws(sql: String, args: Array<String> = emptyArray()): List<Law> {
        val out = ArrayList<Law>()
        db.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) out.add(
                Law(c.getInt(0), c.getString(1), c.getString(2),
                    c.getInt(3), c.getInt(4), c.getString(5))
            )
        }
        return out
    }
    private val lawColumns = "SELECT id,book,section,chapter,halakhah,body FROM laws "
    fun books(): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery("SELECT DISTINCT book FROM laws ORDER BY book", null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }
    fun sections(book: String): List<String> {
        val out = mutableListOf<String>()
        db.rawQuery("SELECT section FROM laws WHERE book=? GROUP BY section ORDER BY MIN(id)", arrayOf(book)).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        return out
    }
    fun chapters(book: String, section: String): List<Int> {
        val out = mutableListOf<Int>()
        db.rawQuery("SELECT DISTINCT chapter FROM laws WHERE book=? AND section=? ORDER BY chapter", arrayOf(book,section)).use { c ->
            while (c.moveToNext()) out.add(c.getInt(0))
        }
        return out
    }
    fun chapter(book: String, section: String, chapter: Int): List<Law> =
        laws(lawColumns + "WHERE book=? AND section=? AND chapter=? ORDER BY halakhah",
            arrayOf(book,section,chapter.toString()))
    fun find(id: Int): Law? = laws(lawColumns + "WHERE id=?", arrayOf(id.toString())).firstOrNull()
    fun findBookmarks(ids: List<Int>): List<Law> = ids.mapNotNull { find(it) }

    private val searchStopWords = setOf(
        "האמ", "צריך", "אני", "מה", "אדמ", "דינ", "שאני",
        "לפי", "הוא", "אפשר", "כתבו", "הלכה", "הרמבמ"
    )

    // Source-finder ranking only. These associations prioritize retrieval,
    // NEVER assert equivalent halakhic rulings or generate legal answers.
    fun search(input: String, limit: Int = 50): List<Law> {
        val base = HebrewSearch.normalize(input)
        if (base.isBlank()) return emptyList()
        val words = base.split(" ").filter {
            it.length >= 2 && !searchStopWords.contains(it)
        }.distinct().take(10)
        if (words.isEmpty()) return emptyList()
        val variants = HebrewSearch.alternatives(input).drop(1)
            .map { HebrewSearch.normalize(it) }
            .filter { it.isNotBlank() }.distinct()
        val queries = mutableListOf<String>()
        if ("יעלה" in base) {
            queries.add("יעלה* AND ויבא*")
            queries.add("שכח* AND יעלה*")
        }
        if ("מזונ" in base) queries.add("שכח* AND מזונ*")
        queries.add(words.joinToString(" AND ") { it + "*" })
        queries.add(words.take(5).joinToString(" OR ") { it + "*" })
        variants.forEach { phrase ->
            queries.add(phrase.split(" ").filter { it.length >= 2 }
                .joinToString(" AND ") { it + "*" })
        }
        val collected = LinkedHashMap<Int, Law>()
        queries.filter { it.isNotBlank() }.distinct().take(24).forEach { ftsQuery ->
            try {
                val found = laws(lawColumns +
                    "WHERE id IN (SELECT rowid FROM laws_fts WHERE laws_fts MATCH ? LIMIT 250) LIMIT 250",
                    arrayOf(ftsQuery))
                found.forEach { collected.putIfAbsent(it.id, it) }
            } catch (_: Exception) {
                // Keep a functioning lexical fallback if FTS is absent.
            }
        }
        if (collected.isEmpty()) {
            laws(lawColumns + "WHERE norm LIKE ? LIMIT 250",
                arrayOf("%" + words.first() + "%")).forEach {
                collected[it.id] = it
            }
        }
        fun score(law: Law): Int {
            val text = HebrewSearch.normalize(law.text)
            val section = HebrewSearch.normalize(law.section)
            var rank = 0
            for (word in words) {
                if (text.contains(word)) rank += 5 + word.length / 2
                if (section.contains(word)) rank += 12
            }
            for (phrase in variants) {
                if (text.contains(phrase)) rank += 8
                if (section.contains(phrase)) rank += 10
            }
            if (text.contains(base)) rank += 45
            if (("מזונ" in base || "ברכת" in base) && "ברכות" in section) rank += 40
            if (("תפילה" in base || "תפל" in base) && "תפילה" in section) rank += 30
            if ("יעלה" in base && "יעלה ויבא" in text) rank += 50
            if ("שכח" in base && "שכח" in text) rank += 16
            if ("ראש" in base && "ראשי חדש" in text) rank += 12
            return rank
        }
        return collected.values
            .sortedWith(compareByDescending<Law> { score(it) }.thenBy { it.id })
            .take(limit)
    }

}
