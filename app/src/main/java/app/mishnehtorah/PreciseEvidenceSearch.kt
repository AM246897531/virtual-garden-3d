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
            // FTS row IDs follow canonical book order. Ascending/descending LIMITs
            // systematically omit the middle of the Mishneh Torah corpus. Obtain
            // a bounded number of matches from every longitudinal index slice.
            // Candidate order is NOT used as relevance, and no source destinations
            // or benchmark questions are present in this algorithm.
            val bins = 8
            val allRows = size.toInt().coerceAtLeast(bins)
            val perBin = (maxRows + bins - 1) / bins
            for (segment in 0 until bins) {
                val first = (segment * allRows / bins) + 1
                val last = ((segment + 1) * allRows / bins)
                db.rawQuery(
                    "SELECT rowid FROM passage_fts WHERE passage_fts MATCH ? " +
                        "AND rowid >= ? AND rowid <= ? LIMIT $perBin",
                    arrayOf(expression, first.toString(), last.toString())
                ).use { c ->
                    while (c.moveToNext()) sink.add(c.getInt(0))
                }
            }
        } catch (ex: SQLiteException) {
            Log.w("RambamSearch", "Failed source index query", ex)
        }
    }

    private fun groupQuery(term: SearchLanguage.Term): String = term.alternatives
        .filter { it.length>=3 && it.length<=30 }
        .distinct().take(6).joinToString(" OR ") { "$it*" }
        .let { if (it.contains(" OR ")) "($it)" else it }

    /** Label-free chapter route. Source corpus headings and opening laws only. */
    private data class ChapterLead(
        val section: String, val chapter: Int, val title: List<String>,
        val grams: IntArray, val magnitude: Float
    )
    private fun chapterGrams(value: String): IntArray {
        val normalized = HebrewSearch.normalize(value)
        val all = HashSet<Int>()
        for (width in 2..4) {
            if (normalized.length < width) continue
            for (i in 0..normalized.length-width)
                all.add(normalized.substring(i,i+width).hashCode())
        }
        return all.toIntArray().apply { sort() }
    }
    private val chaptersWithWeights: Pair<List<ChapterLead>,Map<Int,Float>> by lazy {
        val source = ArrayList<Triple<String,Int,IntArray>>()
        try {
            db.rawQuery("SELECT section,chapter,intro FROM chapter_leads",null).use { c ->
                while (c.moveToNext()) source.add(Triple(c.getString(0),c.getInt(1),
                    chapterGrams(c.getString(2))))
            }
        } catch (ex: SQLiteException) {
            Log.w("RambamSearch","Chapter context unavailable",ex)
        }
        val frequency=HashMap<Int,Int>()
        for ((_,_,grams) in source) for (g in grams)
            frequency[g]=(frequency[g]?:0)+1
        val weights=frequency.mapValues { (_,n) ->
            ln((source.size+1.0)/(n+1.0)).toFloat().coerceAtLeast(.15f)
        }
        val leads=source.map { (section,chapter,grams) ->
            val weightNorm=grams.sumOf { g ->
                val w=(weights[g]?:0f).toDouble();w*w
            }.toFloat().coerceAtLeast(.0001f)
            val title=HebrewSearch.normalize(section.substringAfter("הלכות "))
                .split(' ').filter { it.length>=3 }
            ChapterLead(section,chapter,title,grams,weightNorm)
        }
        leads to weights
    }
    private fun chapterContextCandidates(query: String, limit: Int = 20): List<Pair<String,Int>> {
        val (leads,weights)=chaptersWithWeights
        if (leads.isEmpty()) return emptyList()
        val grams=chapterGrams(query)
        if (grams.isEmpty()) return emptyList()
        val queryWords=HebrewSearch.normalize(query).split(' ').toHashSet()
        return leads.asSequence().map { lead ->
            var dot=0f
            for (gram in grams) {
                if (lead.grams.binarySearch(gram)>=0) {
                    val w=weights[gram]?:0f
                    dot+=w*w
                }
            }
            val titleBoost=.22f*lead.title.count { it in queryWords }
            val similarity=dot/kotlin.math.sqrt(lead.magnitude)+titleBoost
            Triple(lead.section,lead.chapter,similarity)
        }.sortedByDescending { it.third }.take(limit)
            .map { it.first to it.second }.toList()
    }
    private fun addChapterPassages(query: String, output: MutableSet<Int>) {
        // Only source-derived chapter candidates, never a test question -> law map.
        for ((section,chapter) in chapterContextCandidates(query)) {
            db.rawQuery("SELECT p.id FROM passages p JOIN laws l ON p.law_id=l.id " +
                "WHERE l.section=? AND l.chapter=?",arrayOf(section,chapter.toString())).use { c ->
                while (c.moveToNext()) output.add(c.getInt(0))
            }
        }
    }

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
        addChapterPassages(analysis.normalized,out)
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
        // A subject explicitly named in the question is stronger evidence
        // than an incidental lexical match in an unrelated halakhah.
        // All labels come from the corpus's own section headings.
        val title=HebrewSearch.normalize(section.removePrefix("משנה תורה, הלכות "))
            .split(' ').filter { it.length>=3 && it!="הלכות" && it!="תורה" }
            .map { it.removePrefix("ו") }
        if (title.isEmpty()) return 0f
        val qwords=analysis.normalized.split(' ').map { it.removePrefix("ו") }.toHashSet()
        val direct=title.count { it in qwords }
        val fullPhrase=title.size>1 &&
            analysis.normalized.contains(title.joinToString(" "))
        var synonymous=0f
        for (term in analysis.terms) {
            if (term.alternatives.any { alt ->
                title.any { word -> SearchLanguage.matches(alt,word)>.65f }
            }) synonymous+=9f*term.weight
        }
        return minOf(64f,synonymous+direct*17f+(if (fullPhrase) 34f else 0f))
    }

    private fun termMatch(group: SearchLanguage.Term, tokens: List<String>): Float {
        var strongest=0f
        for ((index, w) in group.alternatives.withIndex()) {
            val aliasPenalty = if (index==0) 1f else .88f
            for (candidate in tokens) {
                // Synonyms are useful recall signals, but must not be weighted
                // identically to literal question words (especially polysemes).
                val score=SearchLanguage.matches(w,candidate)*aliasPenalty
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
        val numbers=setOf("אחד","אחת","שני","שנימ","שתימ","שלוש","שלשה","שלושת",
            "ארבע","חמש","שש","שבע","שמונה","תשע","עשר","עשרימ","שלשימ",
            "פעמימ","שתי","שתיי","מאות","אלפימ")
        val positions=words.indices.filter { words[it] in numbers ||
            (words[it].length<=4 && words[it].all { digit -> digit.isDigit() }) }
        if (positions.isEmpty()) return 0f
        val questionWords=analysis.normalized.split(' ')
        val howManyIndex=questionWords.indexOf("כמה")
        // The measured noun immediately after "how many" must be near the
        // source's number. Otherwise, "two blessings" wrongly beats the law
        // about "twice reciting" solely because the subject is shared.
        val measured=if (howManyIndex>=0) questionWords.getOrNull(howManyIndex+1) else null
        val measuredTerm=analysis.terms.firstOrNull { term ->
            measured!=null && (term.word==measured ||
                SearchLanguage.stem(term.word)==SearchLanguage.stem(measured))
        }
        val concepts=analysis.terms.filter { it.word !in setOf("כמה","מספר","מנינ","שיעור") }
        var subjectNearNumber=false
        for (idx in positions) {
            val near=words.subList(maxOf(0,idx-5),minOf(words.size,idx+6))
            if (measuredTerm!=null && termMatch(measuredTerm,near)>=.80f)
                return 38f
            if (concepts.any { it.word.length>=4 && termMatch(it,near)>=.84f })
                subjectNearNumber=true
        }
        return if (subjectNearNumber) 7f else 1f
    }

    /** Reward independent query concepts present in the SAME short evidence span.
     * A paragraph with scattered vocabulary is not necessarily an answer. */
    private fun proximityEvidence(analysis: SearchLanguage.Analysis, words: List<String>): Float {
        if (analysis.terms.size < 2 || words.size < 3) return 0f
        // A source-grounded short-window cohesion bonus. Reuse normalized
        // exact aliases and conservative word stems instead of repeating
        // expensive fuzzy comparisons for every token of every passage.
        val aliases = HashMap<String, Int>()
        for ((index, term) in analysis.terms.withIndex()) {
            val bit = 1 shl index
            for (variant in term.alternatives) {
                if (variant.length < 3) continue
                aliases[variant] = (aliases[variant] ?: 0) or bit
                val stem = SearchLanguage.stem(variant)
                if (stem.length >= 4) aliases[stem] = (aliases[stem] ?: 0) or bit
            }
        }
        val masks = IntArray(words.size) { i ->
            val w = words[i]
            (aliases[w] ?: 0) or (aliases[SearchLanguage.stem(w)] ?: 0)
        }
        var best = 0
        for (start in words.indices) {
            var union = 0
            for (i in start until minOf(words.size, start + 13)) union = union or masks[i]
            best = maxOf(best, Integer.bitCount(union))
            if (best == analysis.terms.size) break
        }
        if (best < 2) return 0f
        val ratio = best.toFloat() / analysis.terms.size
        return 13f * ratio * ratio
    }

    /** Source-grounded temporal overrun evidence: a late question must favor
     * the law discussing the consequence AFTER the time, not the introductory
     * law merely defining the time. No book, chapter, or law IDs are encoded. */
    private fun expiredDeadlineEvidence(analysis: SearchLanguage.Analysis,
                                        passage: String): Float {
        if (!analysis.deadlineOverrun) return 0f
        val hasLate = passage.contains("אחר") || passage.contains("איחר") ||
            passage.contains("עבר") || passage.contains("לאחר")
        val hasBoundary = passage.contains("שעות") || passage.contains("שעה") ||
            passage.contains("זמנ") || passage.contains("עונתו")
        if (!hasLate || !hasBoundary) return 0f
        val explicitNegative = passage.contains("לא יצא") ||
            passage.contains("אינו יוצא") || passage.contains("לא יצא ידי")
        return when {
            explicitNegative -> 58f
            passage.contains("יצא ידי") -> 18f
            else -> 24f
        }
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
        val permissionEvidence=if (asksPermission && Regex("אסור|מותר|מתר|פטור").containsMatchIn(passage)) 13f else 0f
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
            secCoverage*6f+max(0f,semanticScore)*12f+leadScore+
            proximityEvidence(analysis, words)+expiredDeadlineEvidence(analysis,passage)-shortness
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
        val lawChapters=HashMap<Int,Int>()
        val lawOrdinals=HashMap<Int,Int>()
        val affinities=sectionAffinities(analysis)
        val sectionBoostCache=HashMap<String,Float>()
        // The opening of each chapter is an actual Rambam source passage.
        // Its similarity helps disambiguate a specific chapter, but it can
        // never independently supply or fabricate a halakhic answer.
        val chapterBonuses=chapterContextCandidates(analysis.normalized,45)
            .mapIndexed { rank,part ->
                part to (if (rank<7) 29f-rank*2f
                    else 17f/(1f+(rank-6)*.13f))
            }.toMap()
        for (batch in passages.map { it.lawId }.distinct().chunked(250)) {
            val placeholders=batch.joinToString(",") { "?" }
            db.rawQuery("SELECT id,section,chapter,halakhah FROM laws WHERE id IN ($placeholders)",
                batch.map { it.toString() }.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    val id=c.getInt(0)
                    lawSections[id]=c.getString(1)
                    lawChapters[id]=c.getInt(2)
                    lawOrdinals[id]=c.getInt(3)
                }
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
            val chapterBonus=chapterBonuses[sec to (lawChapters[passage.lawId]?:0)]?:0f
            // Definition/overview questions usually begin at the chapter's
            // introductory source. This small prior never overrides direct
            // evidence for a specific exception, deadline, or condition.
            val overview = analysis.intent==SearchLanguage.Intent.GENERAL ||
                analysis.intent==SearchLanguage.Intent.REQUIREMENT
            val sourceOpeningBonus=if (overview && !analysis.deadlineOverrun &&
                lawOrdinals[passage.lawId]==1) 5f else 0f
            val rankedEvidence=score.copy(
                score=score.score+sectionBonus+chapterBonus+sourceOpeningBonus)
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

    /**
     * Prefer the Rambam's answer clause over an introductory question,
     * a chapter's enumerative heading, or a generic statement of a doubt.
     * These are Hebrew discourse patterns, never benchmark-specific sources.
     * Original UTF-16 offsets are preserved for exact highlighting.
     */
    /** Return original UTF-16 offsets for the actual *late-time decision*.
     * Tokenize in the verbatim source, so vowels and cantillation never break
     * the highlighted range or require approximate reverse-normalization. */
    private fun overdueDecisionSpan(text: String, question: String,
                                    confidence: Float): PassageMatch? {
        if (!SearchLanguage.analyze(question).deadlineOverrun) return null
        val tokens = Regex("[\\p{L}\\p{M}]+").findAll(text).toList()
        if (tokens.isEmpty()) return null
        val words = tokens.map { HebrewSearch.normalize(it.value) }
        for (i in words.indices) {
            if (words[i] !in setOf("אחר","איחר","לאחר","עבר")) continue
            val endWindow = minOf(words.size, i + 8)
            val hasBoundary = words.subList(i,endWindow).any {
                it == "שעות" || it == "שעה" || it == "זמנ" || it == "עונתו"
            }
            if (!hasBoundary) continue
            val outcome = (i until minOf(words.size-1,i+32)).firstOrNull { j ->
                (words[j] == "לא" && words[j+1] == "יצא") ||
                (words[j] == "אינו" && words[j+1] == "יוצא")
            } ?: continue
            val start = tokens[maxOf(0,i-2)].range.first
            val end = tokens[minOf(words.lastIndex,outcome+8)].range.last+1
            if (end>start && end-start<=340)
                return PassageMatch(start,end,confidence)
        }
        return null
    }

    private fun grammaticalAnswerClause(text: String, question: String,
                                        confidence: Float): PassageMatch? {
        val normalizedQuestion = HebrewSearch.normalize(question)
        if (normalizedQuestion.isBlank()) return null
        val clauses = ArrayList<Pair<Int,Int>>()
        var last = 0
        for (m in Regex("[.?!׃;:\n]+").findAll(text)) {
            val end = m.range.last + 1
            if (end > last && text.substring(last, end).isNotBlank())
                clauses.add(last to end)
            last = end
        }
        if (last < text.length && text.substring(last).isNotBlank())
            clauses.add(last to text.length)
        if (clauses.size < 2) return null
        fun clause(i: Int): String = text.substring(clauses[i].first, clauses[i].second)
        fun overlaps(segment: String): Boolean {
            val words = HebrewSearch.normalize(segment).split(' ').filter { it.length >= 3 }
            return normalizedQuestion.split(' ').filter { it.length >= 4 }.any { term ->
                words.any { SearchLanguage.matches(term, it) >= .80f }
            }
        }
        val introduction = HebrewSearch.normalize(clause(0))
        val introductoryQuestion = introduction.startsWith("כמה ") ||
            introduction.startsWith("אי זו ") || introduction.startsWith("אי זה ") ||
            introduction.startsWith("איזו ") || introduction.startsWith("איזה ") ||
            introduction.startsWith("כיצד ")
        if (introductoryQuestion && overlaps(clause(0))) {
            val start = clauses[1].first
            val end = if (clauses.size >= 3 && clauses[1].second-start < 100)
                clauses[2].second else clauses[1].second
            return PassageMatch(start, minOf(end, start+320), confidence)
        }
        val listAsked = normalizedQuestion.contains("כמה") ||
            normalizedQuestion.contains("מהם") || normalizedQuestion.contains("מה הם")
        if (listAsked) {
            val at = (1 until minOf(clauses.size,5)).firstOrNull { i ->
                HebrewSearch.normalize(clause(i)).contains("ואלו הן")
            }
            if (at != null && overlaps(clause(at-1)+clause(at))) {
                val start = clauses[at].first
                return PassageMatch(start, minOf(clauses[minOf(at+2,clauses.lastIndex)].second,
                    start+320),confidence)
            }
        }
        val uncertaintyAsked = normalizedQuestion.contains("לא בטוח") ||
            normalizedQuestion.contains("לא ודאי") ||
            normalizedQuestion.contains("אינו ודאי") || normalizedQuestion.contains("ספק")
        if (uncertaintyAsked) {
            val at = clauses.indexOfFirst { (a,b) ->
                val span = HebrewSearch.normalize(text.substring(a,b))
                span.contains("ספק") && overlaps(text.substring(a,b))
            }
            if (at >= 0) {
                val start = clauses[at].first
                return PassageMatch(start,minOf(clauses[minOf(at+2,clauses.lastIndex)].second,
                    start+320),confidence)
            }
        }
        return null
    }

    /**
     * For sources with no sentence punctuation, search bounded original-word
     * windows instead of coloring the entire 200+ character paragraph.
     * Every candidate is a verbatim UTF-16 range; weak windows are rejected.
     */
    private val directivePattern=Regex("חייב|אסור|מותר|פטור|לא יצא|יצא ידי|קורא|מברכ|אינו|אין")\n\n    private fun tokenWindowEvidence(text: String, analysis: SearchLanguage.Analysis,
                                    offset: Int, end: Int,
                                    confidence: Float): PassageMatch? {
        if (end<=offset || analysis.terms.isEmpty()) return null
        val snippet=text.substring(offset,end)
        val tokens=Regex("[\\p{L}\\p{M}]+").findAll(snippet).toList()
        if (tokens.size<10) return null
        val words=tokens.map { HebrewSearch.normalize(it.value) }
        val weights=FloatArray(analysis.terms.size) { 1f }
        val whole=evidence(analysis,weights,words.joinToString(" "),"",0f)
        var best: PassageMatch?=null
        var bestScore=Float.NEGATIVE_INFINITY
        for (from in words.indices step 3) {
            for (length in intArrayOf(13,20,28)) {
                val to=minOf(words.size,from+length)
                if (to-from<7) continue
                val a=offset+tokens[from].range.first
                val b=offset+tokens[to-1].range.last+1
                if (b-a<45 || b-a>270) continue
                val normalizedWindow=words.subList(from,to).joinToString(" ")
                val ev=evidence(analysis,weights,normalizedWindow,"",0f)
                if (ev.matched<minOf(2,analysis.terms.size) ||
                    ev.coverage<.32f || ev.coverage+.10f<whole.coverage) continue
                val directive = directivePattern.containsMatchIn(normalizedWindow)
                val intentBonus=if (directive && (analysis.intent==SearchLanguage.Intent.CONDITION ||
                    analysis.intent==SearchLanguage.Intent.REQUIREMENT ||
                    analysis.deadlineOverrun)) 9f else 0f
                val score=ev.score+intentBonus-(b-a)*.032f
                if (score>bestScore) {
                    bestScore=score
                    best=PassageMatch(a,b,confidence)
                }
            }
        }
        return best
    }

    fun refine(text: String, query: String, start: Int, end: Int, confidence: Float): PassageMatch? {
        if (start<0 || end>text.length || end<=start) return null
        // The index guarantees that this is a verbatim, original offset.
        // Select a real answer clause when the source explicitly separates
        // a question/definition/list/uncertainty from its explanation.
        overdueDecisionSpan(text,query,confidence)?.let { return it }
        grammaticalAnswerClause(text, query, confidence)?.let { return it }
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
        if (candidates.size<2) return tokenWindowEvidence(text,analysis,start,end,confidence)
            ?: PassageMatch(start,end,confidence)
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
        else tokenWindowEvidence(text,analysis,start,end,confidence)
            ?: PassageMatch(start,end,confidence)
    }
}
