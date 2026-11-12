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
 * EXPERIMENT ONLY. Tests whether a compact, source-verbatim snippet from the
 * existing span or complete law improves answer precision. No gold anchor or
 * expected law is consulted by the candidate window selector.
 *
 * This file is in src/test, not shipped in the application.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RambamHighlightAblationTest {
    private val token=Regex("[\\p{L}\\p{M}]+")
    private val stop=setOf("הרמבמ","הרמבם","מקור","המקור",
        "מקרה","ההלכה","לפי","האם","איפה","מה","אני","צריך",
        "של","את","לי","הוא","היא","אפשר","מהו","מהי")
    private val normative=Regex("אסור|מותר|חיב|חייב|מצוה|פטור|אינו|אין|לא |"
        +"חוזר|נזכר|שעה|זמנ|יחזיר|קורא|מברכ")
    private fun grams(s:String):Set<String> {
        val txt=HebrewSearch.normalize(s)
        return if(txt.length<3) emptySet()
            else (0..txt.length-3).map { txt.substring(it,it+3) }.toSet()
    }
    private fun findBest(text:String, question:String,
                         base:PassageMatch?, whole:Boolean):PassageMatch? {
        if(base==null)return null
        val sourceStart=if(whole)0 else base.start
        val sourceEnd=if(whole)text.length else base.end
        if(sourceStart<0 || sourceEnd>text.length || sourceEnd<=sourceStart)
            return null
        val part=text.substring(sourceStart,sourceEnd)
        val found=token.findAll(part).toList()
        if(found.size<6)return base
        val focus=SearchLanguage.focusedEvidenceQuery(question)
        val qwords=HebrewSearch.normalize(focus).split(' ')
            .filter { it.length>=3 && it !in stop }.toSet()
        if(qwords.isEmpty())return base
        val qstems=qwords.map {
            if(it.length>=5 && it.first() in "בכהלמוש")it.drop(1) else it
        }.toSet()
        val qgrams=grams(focus)
        var best:PassageMatch?=null
        var highest=Float.NEGATIVE_INFINITY
        for (from in found.indices step 2) {
            for (width in intArrayOf(8,12,16,20)) {
                val until=minOf(found.size,from+width)
                if(until-from<5)continue
                val a=sourceStart+found[from].range.first
                val b=sourceStart+found[until-1].range.last+1
                if(b-a<35 || b-a>190)continue
                val raw=text.substring(a,b)
                val normalized=HebrewSearch.normalize(raw)
                val words=normalized.split(' ').toSet()
                val stems=words.map {
                    if(it.length>=5 && it.first() in "בכהלמוש")it.drop(1) else it
                }.toSet()
                val common=qwords.intersect(words).size
                val stemCommon=qstems.intersect(stems).size
                val candidateGrams=grams(normalized)
                val dice=2f*qgrams.intersect(candidateGrams).size/
                    maxOf(1f,(qgrams.size+candidateGrams.size).toFloat())
                val directive=normative.containsMatchIn(normalized)
                val score=common*9f+stemCommon*3f+dice*42f+
                    (if(directive)3.5f else 0f)-(b-a)*.045f
                if(score>highest){
                    highest=score
                    best=PassageMatch(a,b,base.confidence)
                }
            }
        }
        return best?:base
    }

    @Test fun evaluateOnlyVerbatimUntrainedWindowPolices() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(context)
        val raw=context.assets.open("quality_500_gold.json")
            .bufferedReader().use{it.readText()}
        val cases=JSONArray(raw)
        assertEquals(500,cases.length())
        val correct=IntArray(2)
        val wins=Array(3){IntArray(2)}
        val covered=Array(3){IntArray(2)}
        for(i in 0 until 500){
            val item=cases.getJSONObject(i)
            val q=item.getString("question")
            val synthetic=item.optString("origin")=="synthetic-source-derived"
            val cohort=if(synthetic)1 else 0
            val law=store.search(q,1).firstOrNull()
            if (law==null ||
                !law.section.endsWith("הלכות "+item.getString("section")) ||
                law.chapter!=item.getInt("chapter") ||
                law.number!=item.getInt("law"))continue
            correct[cohort]++
            val baseline=store.relevantPassage(law,q)
            val casesToCompare=listOf(baseline,
                findBest(law.text,q,baseline,false),
                findBest(law.text,q,baseline,true))
            val begin=item.getInt("highlight_start_utf16")
            val end=item.getInt("highlight_end_utf16")
            for(policy in 0..2){
                val span=casesToCompare[policy]?:continue
                val valid=span.start>=0 && span.end<=law.text.length &&
                    span.end>span.start && span.end-span.start<=360
                if(!valid)continue
                val overlap=(minOf(span.end,end)-maxOf(span.start,begin))
                    .coerceAtLeast(0)
                val coverage=overlap.toFloat()/(end-begin)
                val precision=overlap.toFloat()/(span.end-span.start)
                val hasGold=HebrewSearch.normalize(
                    law.text.substring(span.start,span.end)
                ).contains(HebrewSearch.normalize(item.getString("highlight_contains")))
                if(coverage>=.90f && precision>=.20f && hasGold)
                    wins[policy][cohort]++
                if(coverage>=.90f)covered[policy][cohort]++
            }
            if((i+1)%100==0)println(
                "KOTLIN_HIGHLIGHT_ABLATION_PROGRESS ${i+1}/500")
        }
        println("KOTLIN_HIGHLIGHT_ABLATION_CORRECT_SOURCE " +
            "manual=${correct[0]}/140 synthetic=${correct[1]}/360")
        for(policy in 0..2)println(
            "KOTLIN_HIGHLIGHT_ABLATION_RESULT policy=$policy " +
            "manual_strict=${wins[policy][0]}/${correct[0]} " +
            "synthetic_strict=${wins[policy][1]}/${correct[1]} " +
            "manual_coverage=${covered[policy][0]} " +
            "synthetic_coverage=${covered[policy][1]}")
    }
}
