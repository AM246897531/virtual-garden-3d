package app.mishnehtorah

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Strict metamorphic ROBUSTNESS gate, NOT 560 independent freeform questions.
 * Four semantically neutral discourse wrappers for each of 140 original
 * genuinely authored questions. Source label and offsets are test fixtures
 * only; none are used in candidate retrieval or source scoring.
 *
 * This runs the exact CorpusStore + PreciseEvidenceSearch + source highlighting
 * of the Android app through Robolectric (JVM); no APK is built.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RambamMetamorphic560Test {
    /**
     * Pure query-understanding acceptance: before ranking any law, confirm
     * that generic search instructions did not erase the actual case and
     * that the focused query is not still mostly procedural padding.
     */
    @Test fun allWrappersKeepTheFactualQuestionWithoutGenericInstructions() {
        val c=RuntimeEnvironment.getApplication() as Context
        val handwritten=ArrayList<String>()
        for(file in listOf("quality_natural_100.json",
                "quality_holdout_nonopening_40.json")){
            val arr=JSONArray(c.assets.open(file)
                .bufferedReader().use { it.readText() })
            for(i in 0 until arr.length())
                handwritten.add(arr.getJSONObject(i).getString("question"))
        }
        assertEquals(140,handwritten.size)
        val cases=JSONArray(c.assets.open("quality_560_wrapper_stress.json")
            .bufferedReader().use { it.readText() })
        assertEquals(560,cases.length())
        var retained=0
        var isolated=0
        var strict=0
        val missing=ArrayList<Int>()
        for(i in 0 until cases.length()){
            val item=cases.getJSONObject(i)
            val original=handwritten[item.getInt("parent_question_index")-1]
            val focus=SearchLanguage.focusedEvidenceQuery(item.getString("question"))
            val lawWords=HebrewSearch.normalize(original).split(' ')
                .filter{it.length>=4}.toSet()
            val focusWords=HebrewSearch.normalize(focus).split(' ')
                .filter{it.length>=4}.toSet()
            val common=lawWords.intersect(focusWords).size.toFloat()
            val coverage=common/maxOf(1,lawWords.size)
            val unrelated=(focusWords-lawWords).size
            if(coverage>=.80f)retained++ else missing.add(i+1)
            if(unrelated<=maxOf(2,lawWords.size/3))isolated++
            if(coverage>=.80f && unrelated<=maxOf(2,lawWords.size/3))
                strict++
        }
        println("REAL_KOTLIN_FOCUS_Q560 retained=$retained/560 " +
            "boilerplate_isolated=$isolated/560 both=$strict/560 " +
            "missing_core_case_numbers=${missing.take(25)}")
        assertEquals("An original halakhic condition was lost",560,retained)
        assertEquals("Administrative instruction text still pollutes source evidence",
            560,isolated)
    }

    @Test fun invariantLegalQuestionsPreserveTopOneAndPreciseAnswers() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(context)
        val data=context.assets.open("quality_560_wrapper_stress.json")
            .bufferedReader().use{it.readText()}
        val qa=JSONArray(data)
        assertEquals("560 invariant questions are required",560,qa.length())
        val first=IntArray(4)
        val precise=IntArray(4)
        val empty=IntArray(4)
        val timings=LongArray(560)
        for(i in 0 until qa.length()) {
            val case=qa.getJSONObject(i)
            val variant=case.getInt("variant")-1
            assertTrue(variant in 0..3)
            val prompt=case.getString("question")
            val started=System.nanoTime()
            val law=store.search(prompt,1).firstOrNull()
            timings[i]=(System.nanoTime()-started)/1_000_000
            if(law==null)empty[variant]++
            val sourceMatches=law!=null &&
                law.section.endsWith("הלכות "+case.getString("section")) &&
                law.chapter==case.getInt("chapter") &&
                law.number==case.getInt("law")
            if(sourceMatches)first[variant]++
            val match=if(law!=null)store.relevantPassage(law,prompt) else null
            val valid=match!=null && law!=null &&
                match.start>=0 && match.end<=law.text.length &&
                match.end>match.start && match.end-match.start<=360
            val goldBegin=case.getInt("highlight_start_utf16")
            val goldEnd=case.getInt("highlight_end_utf16")
            val overlap=if(valid)
                (minOf(match!!.end,goldEnd)-maxOf(match.start,goldBegin))
                    .coerceAtLeast(0) else 0
            val coverage=if(goldEnd>goldBegin)
                overlap.toFloat()/(goldEnd-goldBegin) else 0f
            val precision=if(valid)
                overlap.toFloat()/(match!!.end-match.start) else 0f
            val phraseSupported=valid && law!=null &&
                HebrewSearch.normalize(law.text.substring(match!!.start,match.end))
                    .contains(HebrewSearch.normalize(case.getString("highlight_contains")))
            val evidenceCorrect=sourceMatches && phraseSupported &&
                coverage>=.90f && precision>=.20f
            if(evidenceCorrect)precise[variant]++
            if(!evidenceCorrect)println(
                "REAL_KOTLIN_METAMORPHIC_FAIL index=${i+1} " +
                "variant=${variant+1} original=${case.getInt("parent_question_index")} " +
                "firstSource=$sourceMatches validHighlight=$valid " +
                "coverage=$coverage precision=$precision " +
                "actual=${law?.reference ?: "none"}")
            if((i+1)%140==0)println(
                "REAL_KOTLIN_METAMORPHIC_VARIANT variant=${variant+1} " +
                "top1=${first[variant]}/140 highlight=${precise[variant]}/140 " +
                "empty=${empty[variant]}/140")
        }
        val sorted=timings.sorted()
        println("REAL_KOTLIN_METAMORPHIC_Q560_SUMMARY " +
            "top1=${first.sum()}/560 preciseAnswer=${precise.sum()}/560 " +
            "empty=${empty.sum()}/560 median_ms=${sorted[280]} " +
            "p95_ms=${sorted[531]} provenance=140_human_times_4_wrappers apk=false")
        assertEquals("Top-1 must be invariant for all wrapper questions",560,first.sum())
        assertEquals("Source-grounded answer spans must pass all wrapper cases",560,precise.sum())
    }
}
