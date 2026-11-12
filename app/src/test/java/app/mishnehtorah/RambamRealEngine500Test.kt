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
 * Full source-engine 500-question acceptance, WITHOUT Android APK generation.
 * Runs the REAL source-only Kotlin retrieval and original-text highlight code
 * inside Robolectric Android framework shadows using the bundled SQLite.
 *
 * Includes 140 pre-existing Hebrew questions and 360 explicitly source-derived
 * synthetic cases; a passing suite still must not be called 500 independently
 * paraphrased natural-language questions or a universal 100% guarantee.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RambamRealEngine500Test {
    @Test fun fiveHundredStrictTopOneAndAnswerSpans() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(context)
        val raw=context.assets.open("quality_500_gold.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases=JSONArray(raw)
        assertEquals("The 500-case data asset is missing",500,cases.length())
        val timings=ArrayList<Long>(500)
        var top1=0
        var completeAnswer=0
        var manualTop1=0
        var manualAnswer=0
        var generatedTop1=0
        var generatedAnswer=0
        for (i in 0 until cases.length()) {
            val item=cases.getJSONObject(i)
            val question=item.getString("question")
            val synthetic=item.optString("origin")=="synthetic-source-derived"
            val begin=System.nanoTime()
            val returned=store.search(question,1)
            val law=returned.firstOrNull()
            val millis=(System.nanoTime()-begin)/1_000_000
            timings.add(millis)
            val correct=law!=null &&
                law.section.endsWith("הלכות "+item.getString("section")) &&
                law.chapter==item.getInt("chapter") &&
                law.number==item.getInt("law")
            if (correct) {
                top1++
                if (synthetic) generatedTop1++ else manualTop1++
            }
            val evidence=if (law!=null) store.relevantPassage(law,question) else null
            val inBounds=evidence!=null && law!=null &&
                evidence.start>=0 && evidence.end<=law.text.length &&
                evidence.end>evidence.start && evidence.end-evidence.start<=360
            val beginGold=item.optInt("highlight_start_utf16",-1)
            val endGold=item.optInt("highlight_end_utf16",-1)
            val cover=if (inBounds && beginGold>=0 && endGold>beginGold)
                (minOf(evidence!!.end,endGold)-maxOf(evidence.start,beginGold))
                    .coerceAtLeast(0).toFloat()/(endGold-beginGold) else 0f
            val precision=if (inBounds)
                (minOf(evidence!!.end,endGold)-maxOf(evidence.start,beginGold))
                    .coerceAtLeast(0).toFloat()/(evidence.end-evidence.start) else 0f
            val exactText=inBounds && law!=null && HebrewSearch.normalize(
                law.text.substring(evidence!!.start,evidence.end)
            ).contains(HebrewSearch.normalize(item.getString("highlight_contains")))
            val good=correct && inBounds && exactText && cover>=.90f && precision>=.20f
            if (good) {
                completeAnswer++
                if (synthetic) generatedAnswer++ else manualAnswer++
            }
            val status=if(good) "PASS" else "FAIL"
            println("REAL_KOTLIN_ROBOLECTRIC_Q500 ${i+1}/500 $status " +
                "source=${law?.reference ?: "none"} " +
                "expected=${item.getString("section")}/${item.getInt("chapter")}/${item.getInt("law")} " +
                "cohort=${if (synthetic) "synthetic" else "reviewed"} " +
                "top1=$correct evidence=$good goldCoverage=$cover precision=$precision " +
                "time_ms=$millis")
            if ((i+1)%50==0) println(
                "REAL_KOTLIN_ROBOLECTRIC_Q500_PROGRESS ${i+1}/500 " +
                "top1=$top1 exactAnswer=$completeAnswer")
        }
        val sorted=timings.sorted()
        println("REAL_KOTLIN_ROBOLECTRIC_Q500_SUMMARY " +
            "top1=$top1/500 preciseEvidence=$completeAnswer/500 " +
            "reviewedTop1=$manualTop1/140 reviewedEvidence=$manualAnswer/140 " +
            "syntheticTop1=$generatedTop1/360 syntheticEvidence=$generatedAnswer/360 " +
            "p50_ms=${sorted[250]} p95_ms=${sorted[474]} " +
            "apk=false source_engine_real=true jvm_android_shadow=true")
        // A rigorous gate: no mocked 500/500 claim and no silent partial pass.
        assertEquals("Correct law not first for every case",500,top1)
        assertEquals("Turquoise source-span incomplete for some cases",
            500,completeAnswer)
        assertTrue("No valid performance samples",sorted.isNotEmpty())
    }
}
