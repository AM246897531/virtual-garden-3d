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
 * EXPERIMENT ONLY: score decomposition ablations against the actual
 * 15,063-law Kotlin SQLite engine and its existing 100+40 source labels.
 *
 * Expected law IDs are NEVER given to retrieval. The test scores its
 * returned first law only AFTER search; it does not change production
 * weight defaults. No APK is built.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class RambamRankingWeightAblationTest {
    @Test fun compareElevenRealKotlinPolicies() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(context)
        val default=PreciseEvidenceSearch.RankingWeights()
        val profiles=listOf(
            "baseline" to default,
            "no_section" to default.copy(sectionScale=0f),
            "half_section" to default.copy(sectionScale=.5f),
            "double_section" to default.copy(sectionScale=2f),
            "no_chapter" to default.copy(chapterScale=0f),
            "half_chapter" to default.copy(chapterScale=.5f),
            "double_chapter" to default.copy(chapterScale=2f),
            "no_char" to default.copy(charScale=0f),
            "triple_char" to default.copy(charScale=3f),
            "no_opening" to default.copy(openingScale=0f),
            "triple_opening" to default.copy(openingScale=3f),
            "half_chapter_half_section" to
                default.copy(chapterScale=.5f,sectionScale=.5f)
        )
        val suites=listOf(
            "quality_natural_100.json" to 100,
            "quality_holdout_nonopening_40.json" to 40
        )
        val totals=Array(profiles.size){IntArray(2)}
        val times=Array(profiles.size){LongArray(2)}
        for((suiteIndex,pair) in suites.withIndex()) {
            val (path,count)=pair
            val arr=JSONArray(context.assets.open(path)
                .bufferedReader().use { it.readText() })
            assertEquals(count,arr.length())
            for((policyIndex,p) in profiles.withIndex()) {
                val (name,weights)=p
                store.setExperimentalRankingWeights(weights)
                for(i in 0 until count) {
                    val gold=arr.getJSONObject(i)
                    val question=gold.getString("question")
                    val started=System.nanoTime()
                    val found=store.search(question,1).firstOrNull()
                    times[policyIndex][suiteIndex]+=
                        (System.nanoTime()-started)/1_000_000
                    if(found!=null &&
                        found.section.endsWith("הלכות "+gold.getString("section")) &&
                        found.chapter==gold.getInt("chapter") &&
                        found.number==gold.getInt("law"))
                        totals[policyIndex][suiteIndex]++
                }
                println("REAL_KOTLIN_RANK_WEIGHT policy=$name " +
                    "suite=${if(suiteIndex==0) "Q100" else "Q40"} " +
                    "correct=${totals[policyIndex][suiteIndex]}/$count " +
                    "avg_ms=${times[policyIndex][suiteIndex]/count}")
            }
        }
        store.setExperimentalRankingWeights(default)
        for((index,profile) in profiles.withIndex()) {
            println("REAL_KOTLIN_RANK_WEIGHT_RESULT policy=${profile.first} " +
                "first100=${totals[index][0]}/100 " +
                "new40=${totals[index][1]}/40")
        }
    }
}
