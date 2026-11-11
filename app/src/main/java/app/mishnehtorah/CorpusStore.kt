package app.mishnehtorah

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import android.util.Log
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
        "שכח" to listOf("שכח", "נזכר", "לא אמר", "לא הזכיר"),
        "להזכיר" to listOf("הזכיר", "לא הזכיר", "אמר"),
        "ראש חודש" to listOf("ראשי חדשים", "ראש חדש", "יעלה ויבא"),
        "ראש חדש" to listOf("ראשי חדשים", "ראש חדש", "יעלה ויבא"),
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

class CorpusStore private constructor(
    private val db: SQLiteDatabase, private val semantic: SemanticIndex,
    private val characterIndex: CharTrigramIndex?
) {
    companion object {
        suspend fun open(context: Context): CorpusStore = withContext(Dispatchers.IO) {
            val destination = File(context.filesDir, "rambam.sqlite")
            // Previous builds shipped a differently-versioned database with the
            // same app ID. Migrate once on upgrade instead of silently reusing it.
            val prefs = context.getSharedPreferences("corpus_install", Context.MODE_PRIVATE)
            val corpusEpoch = 5
            if (!destination.exists() || destination.length() == 0L ||
                prefs.getInt("corpus_epoch", 0) != corpusEpoch) {
                val temporary = File(context.filesDir, "rambam.sqlite.part")
                if (temporary.exists()) temporary.delete()
                context.assets.open("rambam.sqlite").use { input ->
                    temporary.outputStream().use { output -> input.copyTo(output) }
                }
                check(temporary.length() > 1_000_000L) { "Corpus extraction incomplete" }
                if (destination.exists()) check(destination.delete()) { "Cannot replace old corpus" }
                check(temporary.renameTo(destination)) { "Unable to move corpus database" }
                prefs.edit().putInt("corpus_epoch", corpusEpoch).commit()
            }
            // Never label a lexical-only installation as smart search.
            val model = SemanticIndex.load(context)
            val characterIndex = CharTrigramIndex.load(context)
            val opened = SQLiteDatabase.openDatabase(destination.absolutePath, null,
                SQLiteDatabase.OPEN_READONLY)
            try {
                opened.rawQuery("SELECT COUNT(*) FROM laws", null).use { c ->
                    check(c.moveToFirst() && c.getInt(0) >= 15_000) {
                        "Bundled corpus is incomplete"
                    }
                }
                Log.i("RambamSearch", "SMART_SEARCH_READY corpus=valid semantic=loaded " +
                    "char3=${if (characterIndex != null) "loaded" else "unavailable"}")
                CorpusStore(opened, model, characterIndex)
            } catch (error: Exception) {
                opened.close()
                throw error
            }
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

    private data class IndexedLaw(val law: Law, val normalizedBody: String)
    private fun indexedLaws(ids: Collection<Int>): List<IndexedLaw> {
        if (ids.isEmpty()) return emptyList()
        val output = ArrayList<IndexedLaw>(ids.size)
        for (batch in ids.chunked(280)) {
            val marks = batch.joinToString(",") { "?" }
            db.rawQuery("SELECT id,book,section,chapter,halakhah,body,norm FROM laws " +
                "WHERE id IN ($marks)", batch.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    output.add(IndexedLaw(Law(c.getInt(0), c.getString(1), c.getString(2),
                        c.getInt(3), c.getInt(4), c.getString(5)), c.getString(6)))
                }
            }
        }
        return output
    }

    private val preciseSearch = PreciseEvidenceSearch(db, semantic, characterIndex)
    private val highlightCache = object : LinkedHashMap<String, PassageMatch?>(180, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, PassageMatch?>?) =
            size > 180
    }

    internal fun relevantPassage(law: Law, question: String): PassageMatch? {
        if (question.isBlank()) return null
        val key = "${law.id}:$question"
        synchronized(highlightCache) {
            if (highlightCache.containsKey(key)) return highlightCache[key]
        }
        // When a law was opened outside search, run content-only matching with
        // the original passage finder; never use a predetermined source hint.
        val passage = RelevantPassage.find(law.text, question, "", semantic)
            ?: SearchLanguage.sourceVocabularyBridges(question).firstNotNullOfOrNull { bridge ->
                RelevantPassage.find(law.text, bridge, "", semantic)
            }
        synchronized(highlightCache) { highlightCache[key] = passage }
        return passage
    }

    /** Search actual passages, then open the corresponding *verbatim* law. */
    fun search(input: String, limit: Int = 22): List<Law> {
        if (input.isBlank()) return emptyList()
        val hits = preciseSearch.search(input, limit)
        if (hits.isEmpty()) {
            Log.i("RambamSearch", "RESULT count=0 model_candidates=240 top=none")
            return emptyList()
        }
        val lawById = indexedLaws(hits.map { it.lawId }).associateBy { it.law.id }
        val output = ArrayList<Law>(hits.size)
        for (hit in hits) {
            val law = lawById[hit.lawId]?.law ?: continue
            val focus = preciseSearch.refine(law.text,input,hit.start,hit.end,hit.confidence)
            synchronized(highlightCache) { highlightCache["${law.id}:$input"] = focus }
            output.add(law)
        }
        Log.i("RambamSearch", "RESULT count=${output.size} model_candidates=240 " +
            "top=${output.firstOrNull()?.reference ?: "none"} " +
            "confidence=${hits.firstOrNull()?.confidence ?: 0f}")
        return output
    }
}
