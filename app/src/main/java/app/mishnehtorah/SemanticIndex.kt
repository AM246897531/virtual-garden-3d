package app.mishnehtorah

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt
import java.util.PriorityQueue

/**
 * Compact learned Hebrew semantic embedding index. Trained solely on the licensed
 * Mishneh Torah text at build time. No ONNX, no server, no runtime download.
 *
 * It retrieves sources; it never invents halakhic conclusions.
 */
class SemanticIndex private constructor(
    private val dimensions: Int,
    private val terms: Map<String, Term>,
    private val buckets: ByteArray,
    private val bucketsCount: Int,
    private val bucketScale: Float,
    private val minN: Int,
    private val maxN: Int,
    private val ids: IntArray,
    private val documentVectors: ByteArray
) {
    private class Term(val weight: Float, val vector: ByteArray)

    companion object {
        fun load(context: Context): SemanticIndex {
            val bytes = context.assets.open("hebrew_semantic.bin").use { it.readBytes() }
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = ByteArray(8)
            buffer.get(magic)
            require(String(magic, Charsets.US_ASCII) == "RAMSEM01") { "Unsupported Hebrew model" }
            val dim = buffer.int
            val nTerms = buffer.int
            val nDocs = buffer.int
            val nBuckets = buffer.int
            val subwordConfig = buffer.int
            val scale = buffer.float
            require(dim in 16..384 && nTerms in 1000..100000 &&
                nDocs in 15000..30000 && nBuckets in 1000..100000 &&
                scale > 0f && scale.isFinite()) { "Invalid Hebrew model header" }
            val tokens = HashMap<String, Term>(nTerms * 2)
            repeat(nTerms) {
                val size = buffer.short.toInt() and 0xffff
                val weight = buffer.float
                require(size in 1..255 && weight.isFinite()) { "Invalid word entry" }
                val word = ByteArray(size)
                buffer.get(word)
                val vector = ByteArray(dim)
                buffer.get(vector)
                tokens[String(word, Charsets.UTF_8)] = Term(weight, vector)
            }
            val bucketWeights = ByteArray(dim * nBuckets)
            buffer.get(bucketWeights)
            val lawIds = IntArray(nDocs)
            val lawVectors = ByteArray(nDocs * dim)
            for (i in 0 until nDocs) {
                lawIds[i] = buffer.int
                buffer.get(lawVectors, i * dim, dim)
            }
            require(!buffer.hasRemaining() && tokens.size == nTerms) { "Corrupt Hebrew index" }
            return SemanticIndex(dim, tokens, bucketWeights, nBuckets, scale,
                subwordConfig ushr 16, subwordConfig and 0xffff, lawIds, lawVectors)
        }
    }

    private val stop = setOf("אני", "מה", "הוא", "היא", "הם", "הן", "את", "של", "על",
        "עם", "או", "כי", "לא", "אין", "יש", "אם", "אף", "כל", "גם", "לו", "לה", "בו",
        "זה", "זו", "כך", "כדי", "מן", "אשר", "דבר", "אחר", "אחד", "שני", "שלא",
        "יהיה", "הרי", "אבל", "ואם", "ואין", "ולא", "לפי", "בין", "כגון", "אלא", "עד",
        "היה", "היו", "האם", "צריך", "אדם", "כיצד", "במה", "כמו", "אינו", "אינה",
        "כלום", "בדבר", "בזה", "אותו", "אותה", "שיש", "הלכה", "עשה")

    /** Gensim fastText compatible signed UTF-8 byte FNV-1a, modulo nBuckets. */
    private fun hash(text: String): Int {
        var h = -2128831035 // unsigned FNV offset basis 2166136261
        for (b in text.toByteArray(Charsets.UTF_8)) {
            h = (h xor b.toInt()) * 16777619 // signed UTF-8 byte is intentional
        }
        return ((h.toLong() and 0xffffffffL) % bucketsCount).toInt()
    }

    private fun unseenVector(word: String): FloatArray? {
        val cps = ("<" + word + ">").codePoints().toArray()
        val matches = HashSet<Int>()
        for (i in cps.indices) {
            for (n in minN..maxN) {
                if (i + n > cps.size) continue
                val fragment = String(cps, i, n)
                matches.add(hash(fragment))
            }
        }
        if (matches.size < 2) return null
        val v = FloatArray(dimensions)
        for (idx in matches) {
            val offset = idx * dimensions
            for (j in v.indices) v[j] += buckets[offset + j].toFloat() * bucketScale
        }
        return normalize(v)
    }

    private fun normalize(v: FloatArray): FloatArray? {
        var power = 0.0
        for (f in v) power += f * f
        if (power < 1e-10) return null
        val inv = (1.0 / sqrt(power)).toFloat()
        for (i in v.indices) v[i] *= inv
        return v
    }

    private fun queryVector(query: String): FloatArray? {
        val q = HebrewSearch.normalize(query).split(" ").filter {
            it.length >= 2 && it !in stop
        }.distinct().take(16)
        if (q.isEmpty()) return null
        val result = FloatArray(dimensions)
        var used = 0
        for (word in q) {
            val term = terms[word]
            val vector: FloatArray
            val weight: Float
            if (term != null) {
                vector = FloatArray(dimensions) { term.vector[it].toFloat() / 127f }
                weight = term.weight
            } else {
                vector = unseenVector(word) ?: continue
                weight = 1.8f
            }
            for (i in 0 until dimensions) result[i] += vector[i] * weight
            used++
        }
        return if (used == 0) null else normalize(result)
    }

    /** Batch clause-level similarity; query embedding is computed only once. */
    fun similarities(question: String, passages: List<String>): FloatArray {
        val q = queryVector(question) ?: return FloatArray(passages.size)
        return FloatArray(passages.size) { index ->
            val p = queryVector(passages[index])
            if (p == null) 0f else {
                var dot = 0f
                for (j in 0 until dimensions) dot += q[j] * p[j]
                dot
            }
        }
    }

    /** Id-to-similarity scores, retaining at most [limit] top model matches. */
    fun nearest(input: String, limit: Int = 140): List<Pair<Int, Float>> {
        val q = queryVector(input) ?: return emptyList()
        val top = PriorityQueue<Pair<Int, Float>>(limit + 1,
            compareBy<Pair<Int, Float>> { it.second }.thenByDescending { it.first })
        // O(documents * dimensions + documents * log(K)) without sorting or
        // allocating 15,063 pairs on every search.
        for (index in ids.indices) {
            val offset = index * dimensions
            var dot = 0f
            for (j in 0 until dimensions) {
                dot += documentVectors[offset + j].toInt() * q[j]
            }
            val similarity = dot / 127f
            if (top.size < limit) {
                top.offer(ids[index] to similarity)
            } else if (similarity > top.peek().second) {
                top.poll()
                top.offer(ids[index] to similarity)
            }
        }
        return top.sortedWith(compareByDescending<Pair<Int, Float>> { it.second }
            .thenBy { it.first })
    }
}
