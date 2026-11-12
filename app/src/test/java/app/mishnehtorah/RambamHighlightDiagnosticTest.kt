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

/** Source-only diagnostic on reviewed Q100, never a source ID lookup. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RambamHighlightDiagnosticTest {
    @Test fun inspectWrongSourceSpanChoices() = runBlocking {
        val c=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(c)
        val tests=JSONArray(c.assets.open("quality_natural_100.json")
            .bufferedReader().use { it.readText() })
        assertEquals(100,tests.length())
        val check=listOf(8,10,13,18,20,23,24,25,26,29,33,35,36,43,47,48,52)
        for(index in check) {
            val item=tests.getJSONObject(index)
            val question=item.getString("question")
            val expected=item.getString("highlight_contains")
            val chosen=store.search(question,1).firstOrNull()
            val correct=chosen!=null &&
                chosen.section.endsWith("הלכות "+item.getString("section")) &&
                chosen.chapter==item.getInt("chapter") &&
                chosen.number==item.getInt("law")
            val evidence=if(chosen!=null)
                store.relevantPassage(chosen,question) else null
            val span=if(chosen!=null&&evidence!=null &&
                evidence.start>=0 && evidence.end<=chosen.text.length)
                chosen.text.substring(evidence.start,evidence.end)
                    .replace('\n',' ').take(340) else "none"
            val goldA=item.getInt("highlight_start_utf16")
            val goldB=item.getInt("highlight_end_utf16")
            println("REAL_KOTLIN_HIGHLIGHT_DEBUG case=${index+1} " +
                "correct=$correct expected=${item.getString("section")} " +
                "${item.getInt("chapter")}:${item.getInt("law")} " +
                "selected=${chosen?.reference ?: "none"} " +
                "query=$question gold=$expected " +
                "goldOffset=$goldA..$goldB " +
                "selectedOffset=${evidence?.start}..${evidence?.end} " +
                "span=$span")
        }
    }
}
