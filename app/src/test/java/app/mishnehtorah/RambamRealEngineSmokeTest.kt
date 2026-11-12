package app.mishnehtorah

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

/**
 * Exercises the real SQLite/semantic/char3 Kotlin source engine in Android
 * framework shadows, not a Python imitation and not an APK or emulator.
 *
 * The corpus and tiny offline model must be built by the test workflow.
 * This smoke run is diagnostic; DO NOT mistake a successful execution
 * for a perfect-quality pass.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RambamRealEngineSmokeTest {
    @Test fun tenRealSourcesAndVerbatimHighlights() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(context)
        val raw=context.assets.open("quality_natural_100.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        val cases=JSONArray(raw)
        assertTrue("Gold 100 debug asset was not packaged",cases.length()>=100)
        val selected=intArrayOf(0,1,2,5,7,8,15,25,40,80)
        var correct=0
        var validSpans=0
        var exactEvidence=0
        for (i in selected) {
            val item=cases.getJSONObject(i)
            val question=item.getString("question")
            val answers=store.search(question,3)
            val top=answers.firstOrNull()
            val accurate=top!=null &&
                top.section.endsWith("הלכות "+item.getString("section")) &&
                top.chapter==item.getInt("chapter") &&
                top.number==item.getInt("law")
            if (accurate) correct++
            val match=if(top!=null) store.relevantPassage(top,question) else null
            val original=top?.text.orEmpty()
            val bounds=match!=null && match.start>=0 &&
                match.end<=original.length && match.end>match.start &&
                match.end-match.start<=360
            if (bounds) validSpans++
            val required=item.getString("highlight_contains")
            val supported=bounds &&
                HebrewSearch.normalize(original.substring(match!!.start,match.end))
                    .contains(HebrewSearch.normalize(required))
            if (accurate && supported) exactEvidence++
            println("ROBOLECTRIC_REAL_ENGINE_CASE ${i+1}/100 " +
                "first=${top?.reference ?: "none"} " +
                "top1_correct=$accurate span_in_bounds=$bounds " +
                "gold_phrase_covered=$supported")
        }
        println("ROBOLECTRIC_REAL_ENGINE_Q10 top1=$correct/10 " +
            "span_in_bounds=$validSpans/10 gold_phrase=$exactEvidence/10 " +
            "actual_engine=true android_shadow=true apk=false")
        // This asserts the real engine executed; it does NOT assert 100% quality.
        assertTrue("The source search unexpectedly returned no evidence",
            validSpans>0)
    }
}
