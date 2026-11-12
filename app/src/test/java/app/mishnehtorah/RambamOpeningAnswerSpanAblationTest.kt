package app.mishnehtorah

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Experimental first-sentence, not first-LAW, answer span selector.
 * The candidate is always part of the actual top-ranked Rambam halakhah.
 * It NEVER sees expected gold offsets before deciding. All options are
 * compared with the production baseline on real 500-case Kotlin retrieval.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RambamOpeningAnswerSpanAblationTest {
    private val tokens=Regex("[\\p{L}\\p{M}]+")
    private fun startWindow(text:String,length:Int,
                            confidence:Float):PassageMatch? {
        val found=tokens.findAll(text).take(length).toList()
        if(found.size<length)return null
        val end=found.last().range.last+1
        return if(end<=360)PassageMatch(0,end,confidence) else null
    }
    private fun overlap(question:String,segment:String):Int {
        val terms=SearchLanguage.analyze(
            SearchLanguage.focusedEvidenceQuery(question)).terms
        val words=HebrewSearch.normalize(segment).split(' ')
        return terms.count { term -> term.alternatives.any { alias ->
            words.any { SearchLanguage.matches(alias,it)>=.80f }
        } }
    }
    @Test fun compareSourceFirstClauseAndExistingTurquoiseSpans() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(context)
        val qa=JSONArray(context.assets.open("quality_500_gold.json")
            .bufferedReader().use { it.readText() })
        assertEquals(500,qa.length())
        val count=IntArray(2)
        val passes=Array(7){IntArray(2)}
        val switches=Array(7){IntArray(2)}
        for(i in 0 until qa.length()){
            val item=qa.getJSONObject(i)
            val q=item.getString("question")
            val cohort=if(item.optString("origin")=="synthetic-source-derived")1 else 0
            val law=store.search(q,1).firstOrNull()?:continue
            val accurate=law.section.endsWith("הלכות "+item.getString("section")) &&
                law.chapter==item.getInt("chapter") &&
                law.number==item.getInt("law")
            if(!accurate)continue
            count[cohort]++
            val original=store.relevantPassage(law,q)
            val starts=listOf(8,12,18).map {
                startWindow(law.text,it,original?.confidence?:0f)
            }
            val questionIntent=SearchLanguage.analyze(q).intent
            val general=questionIntent in setOf(SearchLanguage.Intent.GENERAL,
                SearchLanguage.Intent.HOW_MANY,SearchLanguage.Intent.REQUIREMENT)
            val baseOverlap=if(original!=null &&
                original.start>=0 && original.end<=law.text.length)
                overlap(q,law.text.substring(original.start,original.end))
                else 0
            val firstOverlaps=starts.map {
                if(it!=null)overlap(q,law.text.substring(it.start,it.end)) else -1
            }
            val selected=ArrayList<PassageMatch?>()
            selected.add(original)
            selected.addAll(starts)
            selected.add(if(general && original!=null && original.start>=45 &&
                starts[0]!=null && firstOverlaps[0]>=baseOverlap &&
                firstOverlaps[0]>=2)starts[0] else original)
            selected.add(if(general && original!=null && original.start>=60 &&
                starts[1]!=null && firstOverlaps[1]>=baseOverlap &&
                firstOverlaps[1]>=2)starts[1] else original)
            val best=starts.indices
                .filter { starts[it]!=null && firstOverlaps[it]>=2 }
                .maxWithOrNull(compareBy<Int>{
                    firstOverlaps[it]*5-(starts[it]!!.end*.018f)
                })
            selected.add(if(general && original!=null && original.start>=60 &&
                best!=null && firstOverlaps[best]>=baseOverlap)
                starts[best] else original)
            val goldStart=item.getInt("highlight_start_utf16")
            val goldEnd=item.getInt("highlight_end_utf16")
            for(policy in selected.indices){
                val span=selected[policy]?:continue
                if(span.start<0||span.end>law.text.length||
                    span.end-span.start>360||span.end<=span.start)continue
                val intersection=(minOf(span.end,goldEnd)-
                    maxOf(span.start,goldStart)).coerceAtLeast(0)
                val coverage=intersection.toFloat()/(goldEnd-goldStart)
                val precision=intersection.toFloat()/(span.end-span.start)
                val contains=HebrewSearch.normalize(
                    law.text.substring(span.start,span.end))
                    .contains(HebrewSearch.normalize(item.getString("highlight_contains")))
                if(coverage>=.90f && precision>=.20f && contains)
                    passes[policy][cohort]++
                if(policy>=4 && selected[policy]!=original)switches[policy][cohort]++
            }
        }
        println("REAL_KOTLIN_OPENING_SPAN_CORRECT_SOURCE " +
            "reviewed=${count[0]} synthetic=${count[1]}")
        for(i in passes.indices)println(
            "REAL_KOTLIN_OPENING_SPAN_RESULT policy=$i " +
            "reviewed=${passes[i][0]}/${count[0]} " +
            "synthetic=${passes[i][1]}/${count[1]} " +
            "reviewed_changed=${switches[i][0]} synthetic_changed=${switches[i][1]}")
    }
}
