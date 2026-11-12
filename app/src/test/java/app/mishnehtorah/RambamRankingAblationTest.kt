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
 * Genuine Kotlin 100+40 source-ranking ablations.
 * Every policy uses ONLY query text and real retrieved law metadata. The
 * expected law is looked at afterwards for the report, NEVER for selection.
 * None of these heuristic policies is automatically deployed into the app.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RambamRankingAblationTest {
    private fun words(value:String)=HebrewSearch.normalize(value).split(' ')
        .filter { it.length>=3 && it!="הלכות" && it!="משנה" && it!="תורה" }.toSet()

    private fun titleOverlap(law:Law, queryWords:Set<String>):Float {
        val section=law.section.substringAfter("הלכות ")
        val title=words(section)
        if(title.isEmpty())return 0f
        val found=title.count { it in queryWords }
        return found.toFloat()/title.size
    }
    private fun choose(laws:List<Law>, question:String, policy:Int):Law? {
        val base=laws.firstOrNull()?:return null
        if(policy==0)return base
        val analysis=SearchLanguage.analyze(
            SearchLanguage.focusedEvidenceQuery(question))
        val overview=!analysis.asksSpecificCase && !analysis.deadlineOverrun
        val qwords=words(question)
        if(policy==1) {
            if(!overview)return base
            return laws.firstOrNull { it.section==base.section && it.number==1 }
                ?: base
        }
        if(policy==2) {
            val titleCandidates=laws.filter { titleOverlap(it,qwords)>=.99f }
            return titleCandidates.firstOrNull()?:base
        }
        if(policy==3) {
            val titles=laws.map { titleOverlap(it,qwords) }
            val max=titles.maxOrNull()?:0f
            if(max<.50f)return base
            return laws[titles.indexOfFirst { it==max }]
        }
        if(policy==4) {
            // Explicit, complete topic match has priority; a generic
            // non-conditional overview MAY prefer the opening of that topic.
            val filtered=laws.filter { titleOverlap(it,qwords)>=.99f }
            val candidates=if(filtered.isNotEmpty())filtered else laws
            val top=candidates.first()
            return if(overview) candidates.firstOrNull {
                it.section==top.section && it.number==1
            }?:top else top
        }
        return base
    }

    @Test fun compareFourUntrainedPoliciesOnRealKotlinSearch() = runBlocking {
        val context=RuntimeEnvironment.getApplication() as Context
        val store=CorpusStore.open(context)
        val suites=listOf(
            "quality_natural_100.json" to 100,
            "quality_holdout_nonopening_40.json" to 40
        )
        val totals=Array(5){IntArray(2)}
        for((suite,expected) in suites.withIndex()) {
            val (file,count)=expected
            val raw=context.assets.open(file).bufferedReader().use{it.readText()}
            val cases=JSONArray(raw)
            assertEquals(count,cases.length())
            for(i in 0 until count){
                val item=cases.getJSONObject(i)
                val query=item.getString("question")
                val laws=store.search(query,15)
                val positions=List(5){ policy->choose(laws,query,policy)}
                val found=positions.map { selected ->
                    selected!=null &&
                    selected.section.endsWith("הלכות "+item.getString("section")) &&
                    selected.chapter==item.getInt("chapter") &&
                    selected.number==item.getInt("law")
                }
                for(p in 0..4)if(found[p]) totals[p][suite]++
                val correctIn15=laws.any {
                    it.section.endsWith("הלכות "+item.getString("section")) &&
                    it.chapter==item.getInt("chapter") &&
                    it.number==item.getInt("law")
                }
                println("REAL_KOTLIN_ABLATION_CASE ${if(suite==0) "Q100" else "Q40"} " +
                    "${i+1}/$count baseline=${found[0]} " +
                    "sameSectionOpening=${found[1]} exactTitle=${found[2]} " +
                    "maxTitle=${found[3]} topicPlusOpening=${found[4]} " +
                    "goldInTop15=$correctIn15")
            }
        }
        for(p in 0..4)println("REAL_KOTLIN_ABLATION_RESULT policy=$p " +
            "train100=${totals[p][0]}/100 holdout40=${totals[p][1]}/40")
    }
}
