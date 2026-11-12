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

/** Real source-only Kotlin A/B: all existing 195 Hebrew synonym keys */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RambamSynonymKeyAblationTest {
    @Test fun compareNormalizedAndLegacySynonymsOnRealKotlin() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(context)
        val suites=listOf("quality_natural_100.json" to 100,
            "quality_holdout_nonopening_40.json" to 40)
        for (normalized in listOf(false,true)) {
            SearchLanguage.useNormalizedSynonymKeys=normalized
            val forms=SearchLanguage.analyze("חברים וסימנים ולשון")
                .terms.flatMap { it.alternatives }.toSet()
            println("REAL_KOTLIN_SYNONYM_LOOKUP normalized=$normalized " +
                "contains_raayav=${"רעיו" in forms} " +
                "contains_mifrasset=${"מפרסת" in forms} " +
                "contains_language=${"בכל" in forms}")
            for((file,total) in suites) {
                val questions=JSONArray(context.assets.open(file)
                    .bufferedReader().use{it.readText()})
                assertEquals(total,questions.length())
                var first=0;var in15=0;var empty=0
                for(i in 0 until total){
                    val item=questions.getJSONObject(i)
                    val results=store.search(item.getString("question"),15)
                    if(results.isEmpty()) empty++
                    val correct: (Law)->Boolean = {law ->
                        law.section.endsWith("הלכות "+item.getString("section")) &&
                            law.chapter==item.getInt("chapter") &&
                            law.number==item.getInt("law")
                    }
                    if(results.firstOrNull()?.let(correct)==true) first++
                    if(results.any(correct))in15++
                }
                println("REAL_KOTLIN_SYNONYM_ABLATION normalized=$normalized " +
                    "suite=${if(total==100)"Q100" else "Q40"} " +
                    "top1=$first/$total candidate15=$in15/$total empty=$empty/$total")
            }
        }
        SearchLanguage.useNormalizedSynonymKeys=false
    }
}
