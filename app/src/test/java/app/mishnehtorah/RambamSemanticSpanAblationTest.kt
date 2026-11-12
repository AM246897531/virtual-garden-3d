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
 * Uses ACTUAL bundled Hebrew model to A/B test alternative source-only
 * sentence span selection against real 500 questions. Gold is score-only:
 * never passed to candidate selection. This class is not production code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RambamSemanticSpanAblationTest {
    private fun literalRatio(question:String,source:String):Float {
        val tokens=HebrewSearch.normalize(SearchLanguage.focusedEvidenceQuery(question))
            .split(' ').filter{it.length>=4}.toSet()
        if(tokens.isEmpty())return 0f
        val sourceWords=HebrewSearch.normalize(source).split(' ').toHashSet()
        return tokens.count{it in sourceWords}.toFloat()/tokens.size
    }
    @Test fun evaluateActualModelForFreeformSnippetDecision() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val model=SemanticIndex.load(context)
        val store=CorpusStore.open(context)
        val data=JSONArray(context.assets.open("quality_500_gold.json")
            .bufferedReader().use { it.readText() })
        assertEquals(500,data.length())
        val thresholds=floatArrayOf(-.01f,.02f,.05f,.10f,.15f)
        val wins=Array(thresholds.size+2){IntArray(2)}
        val select=Array(thresholds.size){IntArray(2)}
        val found=IntArray(2)
        for(i in 0 until 500){
            val item=data.getJSONObject(i)
            val question=item.getString("question")
            val synthetic=item.optString("origin")=="synthetic-source-derived"
            val cohort=if(synthetic)1 else 0
            val law=store.search(question,1).firstOrNull()?:continue
            val correct=law.section.endsWith("הלכות "+item.getString("section")) &&
                law.chapter==item.getInt("chapter") &&
                law.number==item.getInt("law")
            if(!correct)continue
            found[cohort]++
            val baseline=store.relevantPassage(law,question)
            val semantic=RelevantPassage.find(law.text,
                SearchLanguage.focusedEvidenceQuery(question),"",model)
            fun valid(span:PassageMatch?) = span!=null && span.start>=0 &&
                span.end<=law.text.length && span.end>span.start &&
                span.end-span.start<=360
            val canScore=valid(baseline)&&valid(semantic)
            val diff=if(canScore){
                val scores=model.similarities(question,listOf(
                    law.text.substring(baseline!!.start,baseline.end),
                    law.text.substring(semantic!!.start,semantic.end)))
                scores[1]-scores[0]
            }else Float.NEGATIVE_INFINITY
            val literal=literalRatio(question,law.text)
            val spans=ArrayList<PassageMatch?>()
            spans.add(baseline)
            spans.add(semantic)
            for((index,threshold) in thresholds.withIndex()) {
                val chooseAlt=literal<.70f && canScore && diff>threshold
                if(chooseAlt)select[index][cohort]++
                spans.add(if(chooseAlt)semantic else baseline)
            }
            val goldStart=item.getInt("highlight_start_utf16")
            val goldEnd=item.getInt("highlight_end_utf16")
            for(index in spans.indices){
                val span=spans[index]?:continue
                if(!valid(span))continue
                val overlap=(minOf(span.end,goldEnd)-maxOf(span.start,goldStart))
                    .coerceAtLeast(0)
                val coverage=overlap.toFloat()/(goldEnd-goldStart)
                val precision=overlap.toFloat()/(span.end-span.start)
                val contains=HebrewSearch.normalize(
                    law.text.substring(span.start,span.end))
                    .contains(HebrewSearch.normalize(item.getString("highlight_contains")))
                if(coverage>=.90f && precision>=.20f && contains)
                    wins[index][cohort]++
            }
        }
        println("SEMANTIC_SPAN_ABLATION_SOURCES manual=${found[0]} synthetic=${found[1]}")
        for(index in wins.indices){
            println("SEMANTIC_SPAN_ABLATION_RESULT policy=$index " +
                "manual=${wins[index][0]}/${found[0]} " +
                "synthetic=${wins[index][1]}/${found[1]} " +
                "manual_switched=${if(index>=2) select[index-2][0] else -1} " +
                "synthetic_switched=${if(index>=2) select[index-2][1] else -1}")
        }
    }
}
