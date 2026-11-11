package app.mishnehtorah

import android.content.Context
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Immutable, prebuilt, fully offline lexical-recall companion to SemanticIndex.
 *
 * Unlike word-only FTS, trigram TF-IDF can retrieve older spellings and
 * inflections without pretending that three matching letters prove a ruling.
 * Results are CANDIDATES only; precise source evidence and span ranking remain
 * the responsibility of PreciseEvidenceSearch.
 *
 * Binary format is produced by scripts/build_char_trigram_index.py.
 * No full corpus copy or online requests are made at query time.
 */
internal class CharTrigramIndex private constructor(
    private val bytes: ByteArray,
    private val docIds: IntArray,
    private val terms: Map<String, Entry>,
    private val postingBase: Int,
    private val postingsTotal: Int
) {
    private data class Entry(val start: Int, val count: Int, val idf: Float)

    companion object {
        fun load(context: Context): CharTrigramIndex? {
            return try {
                context.assets.open("rambam_char3.bin").use { stream ->
                    val bytes = stream.readBytes()
                    val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                    val magic = ByteArray(8)
                    buf.get(magic)
                    require(String(magic, Charsets.US_ASCII) == "RCHAR301") {
                        "Unsupported character index"
                    }
                    val docCount = buf.int
                    val termCount = buf.int
                    val postCount = buf.int
                    require(docCount in 15000..30000 && termCount in 1000..100000 &&
                        postCount in 10000..12000000) { "Invalid character index dimensions" }
                    val docIds = IntArray(docCount) { buf.int }
                    require(docIds.all { it > 0 } &&
                        docIds.toSet().size == docCount) { "Invalid character index IDs" }
                    val terms = HashMap<String, Entry>(termCount * 2)
                    var expectedStart = 0
                    repeat(termCount) {
                        val size = buf.get().toInt() and 0xff
                        require(size in 1..32) { "Invalid character index term" }
                        val raw = ByteArray(size)
                        buf.get(raw)
                        val name = String(raw, Charsets.UTF_8)
                        val start = buf.int
                        val count = buf.int
                        val idf = buf.float
                        require(start == expectedStart && count >= 0 &&
                            start.toLong()+count <= postCount && idf.isFinite() && idf > 0f) {
                            "Corrupt character index postings"
                        }
                        expectedStart += count
                        require(terms.put(name, Entry(start, count, idf)) == null) {
                            "Duplicate character index term"
                        }
                    }
                    val postingBase = buf.position()
                    require(expectedStart == postCount &&
                        bytes.size.toLong()-postingBase == postCount.toLong()*6L) {
                        "Truncated character index"
                    }
                    Log.i("RambamSearch",
                        "CHAR_TRIGRAM_READY terms=$termCount postings=$postCount")
                    CharTrigramIndex(bytes, docIds, terms, postingBase, postCount)
                }
            } catch (error: Exception) {
                // Old installations without the new, optional index still have
                // a valid semantic+FTS engine and must not fail to open.
                Log.w("RambamSearch", "Char trigram index unavailable; lexical fallback", error)
                null
            }
        }
    }

    /** Exact IDF-weighted cosine search. Every score is grounded in the law text.
     * All postings and document weights are precomputed at corpus-build time.
     * No model, network, or database mutation is performed at search time.
     */
    fun nearest(question: String, limit: Int = 240): List<Pair<Int, Float>> {
        val q = HebrewSearch.normalize(question)
        if (q.length < 3 || limit <= 0) return emptyList()
        val grams = HashMap<String, Int>()
        for (i in 0..(q.length-3)) {
            val gram = q.substring(i, i+3)
            if (terms.containsKey(gram)) grams[gram] = (grams[gram] ?: 0) + 1
        }
        if (grams.isEmpty()) return emptyList()
        val prepared = ArrayList<Pair<Entry, Float>>(grams.size)
        var sumSquares = 0.0
        for ((gram, count) in grams) {
            val entry = terms[gram] ?: continue
            val weighted = ((1.0 + ln(count.toDouble())) * entry.idf).toFloat()
            prepared.add(entry to weighted)
            sumSquares += weighted.toDouble() * weighted
        }
        if (sumSquares <= 0.0) return emptyList()
        val invLength = (1.0 / sqrt(sumSquares)).toFloat()
        val scores = FloatArray(docIds.size)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for ((entry, weight) in prepared) {
            val offset = postingBase.toLong() + entry.start.toLong()*6L
            if (offset > Int.MAX_VALUE) continue
            buffer.position(offset.toInt())
            val queryWeight = weight * invLength / 65535f
            repeat(entry.count) {
                val docIndex = buffer.int
                val docWeight = buffer.short.toInt() and 0xffff
                if (docIndex in scores.indices)
                    scores[docIndex] += queryWeight * docWeight
            }
        }
        val keep = minOf(limit, docIds.size)
        val top = PriorityQueue<Pair<Int, Float>>(keep + 1,
            compareBy<Pair<Int, Float>> { it.second }.thenByDescending { it.first })
        for (i in scores.indices) {
            val sim = scores[i]
            if (!sim.isFinite() || sim <= 0f) continue
            val item = docIds[i] to sim
            if (top.size < keep) top.offer(item)
            else if (sim > top.peek().second) {
                top.poll()
                top.offer(item)
            }
        }
        return top.sortedWith(compareByDescending<Pair<Int, Float>> { it.second }
            .thenBy { it.first })
    }
}
