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
 * Compare STRICT lexical source evidence and progressively weaker matching
 * on 100 existing + 40 independently drafted non-opening-law questions.
 * No gold source is ever used in selection or in query expansion.
 * This evaluates both first choice and Top15 candidate recall; changes are
 * experimental only until source precision improves on BOTH cohorts.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RambamEvidenceGateAblationTest {
    @Test fun actualKotlinParaphraseFilteringPolicies() = runBlocking {
        val c=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(c)
        val defaults=PreciseEvidenceSearch.RankingWeights()
        val profiles=listOf(
            "strict" to defaults,
            "coverage70" to defaults.copy(coverageScale=.70f),
            "coverage50" to defaults.copy(coverageScale=.50f),
            "oneTerm70" to defaults.copy(
                coverageScale=.70f,matchedTermRelaxation=1),
            "oneTerm50" to defaults.copy(
                coverageScale=.50f,matchedTermRelaxation=1),
            "oneTerm25" to defaults.copy(
                coverageScale=.25f,matchedTermRelaxation=1)
        )
        val suites=listOf("quality_natural_100.json" to 100,
            "quality_holdout_nonopening_40.json" to 40)
        val score=Array(profiles.size){Array(2){IntArray(3)}}
        for((suiteIndex,pair) in suites.withIndex()) {
            val (path,count)=pair
            val arr=JSONArray(c.assets.open(path).bufferedReader().use{it.readText()})
            assertEquals(count,arr.length())
            for((policyIndex,policy) in profiles.withIndex()) {
                store.setExperimentalRankingWeights(policy.second)
                for(i in 0 until count){
                    val case=arr.getJSONObject(i)
                    val laws=store.search(case.getString("question"),15)
                    val correct: (Law)->Boolean = { law ->
                        law.section.endsWith("הלכות "+case.getString("section")) &&
                            law.chapter==case.getInt("chapter") &&
                            law.number==case.getInt("law")
                    }
                    if(laws.isEmpty())score[policyIndex][suiteIndex][2]++
                    if(laws.firstOrNull()?.let(correct)==true)
                        score[policyIndex][suiteIndex][0]++
                    if(laws.any(correct))
                        score[policyIndex][suiteIndex][1]++
                }
                val counters=score[policyIndex][suiteIndex]
                println("REAL_KOTLIN_EVIDENCE_GATE policy=${policy.first} " +
                    "suite=${if(suiteIndex==0) "Q100" else "Q40"} " +
                    "first=${counters[0]}/$count " +
                    "goldInTop15=${counters[1]}/$count " +
                    "noResult=${counters[2]}/$count")
            }
        }
        store.setExperimentalRankingWeights(defaults)
    }
}
