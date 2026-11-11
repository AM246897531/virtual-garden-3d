package app.mishnehtorah

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import android.util.Log
import kotlin.math.ln
import kotlin.math.max

/**
 * Source-grounded multi-stage passage retrieval. A hit is accepted only when
 * the actual Rambam passage provides lexical evidence for several concepts in
 * the question; embeddings support the order but never substitute evidence.
 *
 * Does not produce an answer, interpolate a ruling, or hard-code particular
 * section/chapter/halakhah targets to make curated tests pass.
 */
internal class PreciseEvidenceSearch(
    private val db: SQLiteDatabase,
    private val semantic: SemanticIndex
) {
    internal data class Hit(val lawId: Int, val start: Int, val end: Int,
                            val confidence: Float, val matched: Int, val coverage: Float)
    private data class Passage(val id: Int, val lawId: Int,
                               val start: Int, val end: Int, val norm: String)
    private val totalPassages = 1f // Actual count is read from the index.
    private val size: Float by lazy {
        db.rawQuery("SELECT count(*) FROM passages", null).use { c ->
            if (c.moveToFirst()) c.getInt(0).toFloat() else totalPassages
        }
    }

    private fun documentFrequency(word: String): Int = db.rawQuery(
        "SELECT df FROM passage_terms WHERE term=?", arrayOf(word)
    ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun weight(term: SearchLanguage.Term): Float {
        val positive = term.alternatives.filter { it.length>=3 }
        val df = positive.minOfOrNull { documentFrequency(it).takeIf { n -> n>0 } ?: Int.MAX_VALUE }
        val usable = if (df == null || df == Int.MAX_VALUE) 60 else df
        // Spoken paraphrases absent from the corpus must not overpower
        // actual source phrases with an artificial ultra-high IDF.
        if (df == null || df == Int.MAX_VALUE) return .65f * term.weight
        return (ln((size + 10f).toDouble()/(usable + 1f).toDouble()).toFloat())
            .coerceIn(1.1f,6.2f) * term.weight
    }

    private fun ftsIds(expression: String, maxRows: Int, sink: MutableSet<Int>) {
        if (expression.isBlank()) return
        try {
            // FTS4 rowids follow the canonical book ordering. A plain LIMIT
            // silently starves later books even when a word matches everywhere.
            // Sample both ends before applying unbiased evidence re-ranking.
            for ((descending, rows) in listOf(false to maxRows, true to maxOf(150, maxRows / 2))) {
                val order = if (descending) "DESC" else "ASC"
                db.rawQuery("SELECT rowid FROM passage_fts WHERE passage_fts MATCH ? " +
                    "ORDER BY rowid $order LIMIT $rows", arrayOf(expression)).use { c ->
                    while (c.moveToNext()) sink.add(c.getInt(0))
                }
            }
        } catch (ex: SQLiteException) {
            Log.w("RambamSearch", "Failed source index query", ex)
        }
    }

    private fun groupQuery(term: SearchLanguage.Term): String = term.alternatives
        .filter { it.length>=3 && it.length<=30 }
        .distinct().take(5).joinToString(" OR ") { "$it*" }
        .let { if (it.contains(" OR ")) "($it)" else it }

    private fun candidateIds(analysis: SearchLanguage.Analysis): LinkedHashSet<Int> {
        val terms=analysis.terms.sortedByDescending { weight(it) }.take(8)
        val out=LinkedHashSet<Int>(4500)
        val groups=terms.map { groupQuery(it) }.filter { it.isNotBlank() }
        for (i in 0 until minOf(4,groups.size)) {
            for (j in i+1 until minOf(i+3,groups.size)) {
                ftsIds("${groups[i]} AND ${groups[j]}",1000,out)
                if (out.size>=3500) break
            }
            if (out.size>=3500) break
        }
        // Single highly specific topic and non-contiguous paraphrases also
        // need a route into the candidate pool. This is NOT sorted by rowid
        // as the final answer; every candidate is independently reranked.
        for (group in groups.take(8)) {
            ftsIds(group,if (out.size>6500) 800 else 2400,out)
            if (out.size>14500) break
        }
        if (groups.size>=2 && out.size<10000)
            ftsIds(groups.take(6).joinToString(" OR "),1800,out)
        return out
    }

    private fun loadPassages(ids: Collection<Int>): List<Passage> {
        val out=ArrayList<Passage>(ids.size)
        for (batch in ids.chunked(250)) {
            val placeholders=batch.joinToString(",") { "?" }
            db.rawQuery("SELECT id,law_id,start,end,norm FROM passages WHERE id IN ($placeholders)",
                batch.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) out.add(Passage(c.getInt(0),c.getInt(1),
                    c.getInt(2),c.getInt(3),c.getString(4)))
            }
        }
        return out
    }

    private fun addSemanticPassages(nearest: List<Pair<Int,Float>>, ids: MutableSet<Int>) {
        for (batch in nearest.map { it.first }.chunked(250)) {
            if (batch.isEmpty()) continue
            val placeholders=batch.joinToString(",") { "?" }
            db.rawQuery("SELECT id FROM passages WHERE law_id IN ($placeholders)",
                batch.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) ids.add(c.getInt(0))
            }
        }
    }

    /**
     * Unsupervised topic likelihood built from EVERY law in each Rambam section.
     * It resolves homographs such as 'המקדש' (a person performing kiddushin vs
     * the Temple), without mapping any question to a preselected source.
     */
    private fun sectionAffinities(analysis: SearchLanguage.Analysis): Map<String,Float> {
        val queried=analysis.terms.flatMap { it.alternatives }.filter { it.length>=3 }
            .distinct().take(75)
        if (queried.isEmpty()) return emptyMap()
        val sizeOfSection=HashMap<String,Int>()
        db.rawQuery("SELECT section,total FROM section_totals",null).use { c ->
            while (c.moveToNext()) sizeOfSection[c.getString(0)]=c.getInt(1)
        }
        val corpusLength=sizeOfSection.values.sum().toDouble().coerceAtLeast(1.0)
        val placeholders=queried.joinToString(",") { "?" }
        val args=queried.toTypedArray()
        val global=HashMap<String,Int>()
        db.rawQuery("SELECT term,tf FROM global_terms WHERE term IN ($placeholders)",args).use { c ->
            while (c.moveToNext()) global[c.getString(0)]=c.getInt(1)
        }
        val bySection=HashMap<String,MutableMap<String,Int>>()
        db.rawQuery("SELECT section,term,tf FROM section_terms WHERE term IN ($placeholders)",args).use { c ->
            while (c.moveToNext()) {
                bySection.getOrPut(c.getString(0)) { HashMap() }[c.getString(1)]=c.getInt(2)
            }
        }
        val affinities=HashMap<String,Float>()
        for ((section,totalWords) in sizeOfSection) {
            if (totalWords<1) continue
            val freq=bySection[section].orEmpty()
            var score=0f
            for (term in analysis.terms) {
                val count=term.alternatives.maxOfOrNull { freq[it]?:0 }?:0
                if (count<1) continue
                val corpusFreq=term.alternatives.sumOf { global[it]?:0 }.coerceAtLeast(1)
                val ratio=(count.toDouble()/totalWords)*corpusLength/corpusFreq
                score += (kotlin.math.ln(1.0+ratio).toFloat())*term.weight
            }
            if (score>0f) affinities[section]=score
        }
        val maxScore=affinities.values.maxOrNull()?:1f
        return affinities.mapValues { (_,score)->score/maxScore }
    }

    private fun sectionTitleBoost(section: String, analysis: SearchLanguage.Analysis): Float {
        val title=HebrewSearch.normalize(section.removePrefix("משנה תורה, הלכות "))
            .split(' ').filter { it.length>=3 && it!="הלכות" && it!="תורה" }
        if (title.isEmpty()) return 0f
        var total=0f
        for (term in analysis.terms) {
            if (term.alternatives.any { alt -> title.any { word -> SearchLanguage.matches(alt,word)>.65f } })
                total+=10f*term.weight
        }
        return minOf(23f,total)
    }

    private fun termMatch(group: SearchLanguage.Term, tokens: List<String>): Float {
        var strongest=0f
        for (w in group.alternatives) {
            for (candidate in tokens) {
                val score=SearchLanguage.matches(w,candidate)
                if (score>strongest) strongest=score
                if (strongest==1f) return 1f
            }
        }
        return strongest
    }

    /** A quantitative answer must give an amount for the *asked-about noun*,
     * not merely mention another number somewhere in the same law. */
    private fun quantityEvidence(analysis: SearchLanguage.Analysis, words: List<String>): Float {
        if (analysis.intent!=SearchLanguage.Intent.HOW_MANY) return 0f
        val numbers=setOf("אחד","אחת","שני","שנימ","שתימ","שלוש","שלשה", "שלושת", "ארבע",
            "חמש","שש","שבע","שמונה","תשע","עשר","עשרימ","שלשימ")
        val positions=words.indices.filter { words[it] in numbers }
        if (positions.isEmpty()) return 0f
        // The noun following a quantity question determines what is counted.
        val concept=analysis.terms.firstOrNull { it.word!="כמה" && it.word!="מספר" && it.word!="מנינ" }
        for (idx in positions) {
            val near=words.subList(maxOf(0,idx-3),minOf(words.size,idx+4))
            if (concept!=null && concept.word.length>=4 && termMatch(concept,near)>=.84f) return 22f
        }
        return 3f
    }

    private data class Evidence(val score: Float, val matched: Int, val coverage: Float)

    private fun evidence(analysis: SearchLanguage.Analysis,
                         weights: FloatArray, passage: String, section: String,
                         semanticScore: Float): Evidence {
        val words=passage.split(' ').filter { it.length>=3 }
        if (words.isEmpty()) return Evidence(-999f,0,0f)
        var covered=0f;var matched=0;var sectionCovered=0f;var total=0f
        for (i in analysis.terms.indices) {
            val t=analysis.terms[i];val w=weights[i];total+=w
            val m=termMatch(t,words)
            if (m>0f) {covered+=w*m;matched++}
            else if (section.isNotBlank()) {
                val s=termMatch(t,section.split(' '))
                if (s>0f) sectionCovered+=w*s
            }
        }
        val coverage=if (total>0f) covered/total else 0f
        val secCoverage=if (total>0f) sectionCovered/total else 0f
        val directPhrases=analysis.terms.zipWithNext().count { (a,b) ->
            passage.contains(a.word+" "+b.word)
        }
        val shortness=(passage.length-180).coerceAtLeast(0)*.012f
        // A question asking whether something is permitted needs an explicit
        // normative ruling, not merely a narrative with overlapping vocabulary.
        val asksPermission=analysis.normalized.contains("מותר") || analysis.normalized.contains("אסור")
        val permissionEvidence=if (asksPermission && Regex("אסור|מותר|מתר").containsMatchIn(passage)) 20f else 0f
        // A direct, nearby statement of the queried concepts is generally
        // stronger evidence than scattered mentions throughout a long law.
        val lead = words.take(15)
        var leadCovered = 0f
        if (lead.isNotEmpty()) {
            for (i in analysis.terms.indices) {
                leadCovered += weights[i] * termMatch(analysis.terms[i], lead)
            }
        }
        val leadScore = if (total > 0f) 20f * (leadCovered / total) else 0f
        val score=coverage*110f+matched*2.5f+minOf(7f,directPhrases*3f)+permissionEvidence+
            SearchLanguage.intentBoost(analysis.intent,passage)*.85f+quantityEvidence(analysis,words)+
            secCoverage*6f+max(0f,semanticScore)*12f+leadScore-shortness
        return Evidence(score,matched,coverage)
    }

    fun search(query: String, limit: Int = 22): List<Hit> {
        val analysis=SearchLanguage.analyze(query)
        if (analysis.terms.isEmpty()) return emptyList()
        val started=android.os.SystemClock.elapsedRealtime()
        val weights=FloatArray(analysis.terms.size) { weight(analysis.terms[it]) }
        val nearest=semantic.nearest(query,240)
        val semanticScores=nearest.toMap()
        val ids=candidateIds(analysis)
        val lexicalCount=ids.size
        addSemanticPassages(nearest,ids)
        val passages=loadPassages(ids)
        if (passages.isEmpty()) return emptyList()
        val lawSections=HashMap<Int,String>()
        val affinities=sectionAffinities(analysis)
        val sectionBoostCache=HashMap<String,Float>()
        for (batch in passages.map { it.lawId }.distinct().chunked(250)) {
            val placeholders=batch.joinToString(",") { "?" }
            db.rawQuery("SELECT id,section FROM laws WHERE id IN ($placeholders)",
                batch.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) lawSections[c.getInt(0)]=c.getString(1)
            }
        }
        val accepted=HashMap<Int,Pair<Passage,Evidence>>()
        val groupCount=analysis.terms.size
        for (passage in passages) {
            val sec=lawSections[passage.lawId].orEmpty()
            val score=evidence(analysis,weights,passage.norm,
                HebrewSearch.normalize(sec), semanticScores[passage.lawId]?:0f)
            val needed=if (groupCount==1) 1 else 2
            // Penalize partial subject matches. For hard multi-concept queries
            // a passage that lacks the specific condition is not reliable.
            if (score.matched<needed || score.coverage<(if (groupCount<3) .40f else .38f)) continue
            val sectionBonus=sectionBoostCache.getOrPut(sec) {
                (affinities[sec]?:0f)*40f+sectionTitleBoost(sec,analysis)
            }
            val rankedEvidence=score.copy(score=score.score+sectionBonus)
            val old=accepted[passage.lawId]
            if (old==null || rankedEvidence.score>old.second.score)
                accepted[passage.lawId]=passage to rankedEvidence
        }
        val sorted=accepted.entries.asSequence().map { (id,pair) ->
            val (passage,e)=pair
            Hit(id,passage.start,passage.end,e.score,e.matched,e.coverage)
        }.sortedWith(compareByDescending<Hit> { it.confidence }
            .thenByDescending { it.coverage }.thenBy { it.lawId })
            .take(limit).toList()
        Log.i("RambamSearch", "PRECISION_SEARCH lexical_passages=$lexicalCount " +
            "semantic_laws=${nearest.size} rescored=${passages.size} " +
            "supported_laws=${accepted.size} returned=${sorted.size} " +
            "top=${sorted.firstOrNull()?.lawId ?: -1} " +
            "elapsed_ms=${android.os.SystemClock.elapsedRealtime()-started}")
        return sorted
    }

    fun refine(text: String, query: String, start: Int, end: Int, confidence: Float): PassageMatch? {
        if (start<0 || end>text.length || end<=start) return null
        // The index guarantees that this is a verbatim, original offset.
        val analysis=SearchLanguage.analyze(query)
        val initial=text.substring(start,end)
        if (initial.length<=125 || analysis.terms.isEmpty())
            return PassageMatch(start,end,confidence)
        val cut=Regex("[.?!׃;:\\n,،]")
        val candidates=ArrayList<Pair<Int,Int>>()
        var from=0
        for (match in cut.findAll(initial)) {
            val pos=match.range.last+1
            if (pos-from>=35) { candidates.add(from to pos); from=pos }
        }
        if (initial.length-from>=35) candidates.add(from to initial.length)
        if (candidates.size<2) return PassageMatch(start,end,confidence)
        val weights=FloatArray(analysis.terms.size) { 1f }
        val full=evidence(analysis,weights,HebrewSearch.normalize(initial),"",0f)
        var best:Pair<Int,Int>?=null
        var bestScore=Float.NEGATIVE_INFINITY
        for (i in candidates.indices) {
            for (j in i..minOf(i+1,candidates.lastIndex)) {
                val a=candidates[i].first;val b=candidates[j].second
                if (b-a<38 || b-a>230) continue
                val norm=HebrewSearch.normalize(initial.substring(a,b))
                val s=evidence(analysis,weights,norm,"",0f)
                if (s.matched<minOf(2,analysis.terms.size)) continue
                if (s.coverage+0.12f<full.coverage) continue
                val ranked=s.score-(b-a)*.05f
                if (ranked>bestScore) {bestScore=ranked;best=a to b}
            }
        }
        return if (best!=null) PassageMatch(start+best.first,start+best.second,confidence)
        else PassageMatch(start,end,confidence)
    }
}
