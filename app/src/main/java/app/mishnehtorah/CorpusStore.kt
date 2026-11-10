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
        "יעלה ויבוא" to listOf("ראש חודש", "הזכיר", "מאורע"),
        "ברכת המזון" to listOf("ברכת המזון", "מזון"),
        "שכחתי" to listOf("שכח", "לא הזכיר"),
        "לחזור" to listOf("חוזר", "חוזר ומברך"),
        "מותר" to listOf("מותר", "רשאי"),
        "אסור" to listOf("אסור", "אינו רשאי")
    )
    fun normalize(query: String): String {
        var t = Normalizer.normalize(query, Normalizer.Form.NFKC)
        t = marks.replace(t, "")
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
        db.rawQuery("SELECT DISTINCT section FROM laws WHERE book=? ORDER BY MIN(id)", arrayOf(book)).use { c ->
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

    fun search(input: String, limit: Int = 50): List<Law> {
        val base = HebrewSearch.normalize(input)
        if (base.isBlank()) return emptyList()
        val words = base.split(" ").filter { it.length >= 2 }.take(10)
        if (words.isEmpty()) return emptyList()
        val collected = LinkedHashMap<Int, Law>()
        val queries = mutableListOf(words.joinToString(" AND ") { it + "*" })
        // A precise query first; then limited expansions, without treating them as equivalent rulings.
        queries.add(words.take(4).joinToString(" OR ") { it + "*" })
        for (alt in HebrewSearch.alternatives(input).drop(1).take(6)) {
            val normalized = HebrewSearch.normalize(alt)
            if (normalized.isNotBlank()) {
                queries.add(normalized.split(" ").filter { it.isNotBlank() }
                    .joinToString(" AND ") { it + "*" })
            }
        }
        for (ftsQuery in queries) {
            try {
                val found = laws(lawColumns +
                    "WHERE id IN (SELECT rowid FROM laws_fts WHERE laws_fts MATCH ? LIMIT 250) LIMIT ?",
                    arrayOf(ftsQuery, limit.toString()))
                found.forEach { collected.putIfAbsent(it.id, it) }
                if (collected.size >= limit) break
            } catch (_: Exception) { /* FTS may be absent on some Android images */ }
        }
        if (collected.isEmpty()) {
            // Bounded deterministic fallback; still entirely offline.
            val first = words.first()
            laws(lawColumns + "WHERE norm LIKE ? LIMIT ?",
                arrayOf("%" + first + "%", limit.toString())).forEach { collected[it.id] = it }
        }
        return collected.values.take(limit)
    }
}
