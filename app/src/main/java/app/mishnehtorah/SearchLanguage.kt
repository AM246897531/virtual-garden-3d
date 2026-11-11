package app.mishnehtorah

import kotlin.math.ln

/** Language-level normalization, not hand-coded destinations to specific halakhot. */
internal object SearchLanguage {
    internal data class Term(val word: String, val alternatives: List<String>, val weight: Float = 1f)
    internal data class Analysis(val terms: List<Term>, val intent: Intent, val normalized: String)
    internal enum class Intent { GENERAL, WHEN, WHERE, HOW_MANY, REQUIREMENT, CONDITION }

    private val stop = setOf(
        "מה", "מי", "של", "את", "על", "עם", "אני", "אנחנו", "אתה", "שהוא", "שהיא", "האם",
        "אם", "אפשר", "אפשרי", "צריך", "צריכ", "היכנ", "איזה", "איזו", "אלו", "היכן",
        "שאלה", "שואל", "לפי", "רמבמ", "הלכה", "בהלכה", "לפי", "מותר", "אסור", "דינ",
        "הדינ", "אדמ", "מישהוא", "באיזה", "באיזו", "במקרה", "שלי", "שלנו", "אצל", "לגבי",
        "יהיה", "תהיה", "שיש", "שזה", "אותו", "אותה", "משהו", "לעשות", "נעשה", "עשיתי",
        "דבר", "כזה", "בכלל", "למה", "מדוע", "זה", "זאת", "הוא", "היא", "מצב", "צריך"
    )
    // Every mapping captures a synonym or spelling difference, never a predetermined source.
    private val synonyms: Map<String, List<String>> = mapOf(
        "שכח" to listOf("שכח", "שכחתי", "שכחה", "נזכר", "הזכיר", "הזכרה"),
        "שכחתי" to listOf("שכח", "שכחה", "נזכר", "הזכיר"),
        "הזכיר" to listOf("הזכיר", "הזכרה", "שכח", "נזכר"),
        "ראש" to listOf("ראש", "ראשי"),
        "חודש" to listOf("חדש", "חדשים", "חודש", "חודשים"),
        "חודשים" to listOf("חדש", "חדשים", "חודש"),
        "חדש" to listOf("חדש", "חדשים", "חודש"),
        "תפילינ" to listOf("תפילינ", "תפלינ", "תפיל", "תפל"),
        "תפלינ" to listOf("תפילינ", "תפלינ"),
        "תפילה" to listOf("תפילה", "תפלה", "יתפלל", "להתפלל"),
        "להתפלל" to listOf("יתפלל", "תפילה", "תפלה"),
        "חנוכה" to listOf("חנוכה", "חנכה", "החנוכה", "חנוכה"),
        "חנכה" to listOf("חנוכה", "חנכה"),
        "הדלקה" to listOf("להדליק", "מדליק", "הדלקה", "מדליקינ", "דולק"),
        "מדליקים" to listOf("מדליק", "מדליקינ", "להדליק", "הדלקה"),
        "מדליקינ" to listOf("מדליק", "להדליק", "הדלקה"),
        "מדליק" to listOf("מדליק", "להדליק", "הדלקה", "דולק"),
        "להדליק" to listOf("להדליק", "מדליק", "מדליקינ", "הדלקה"),
        "נרות" to listOf("נרות", "נר", "הנרות"),
        "נר" to listOf("נרות", "נר"),
        "ליטול" to listOf("נטל", "נוטל", "נטילה", "ליטול", "נטילת"),
        "נטילת" to listOf("נטל", "נוטל", "נטילה", "ליטול"),
        "אכילה" to listOf("אוכל", "אכילה", "לאכול", "אכל"),
        "לאכול" to listOf("אוכל", "אכילה", "אכל", "לאכול"),
        "אכלתי" to listOf("אכל", "אוכל", "אכילה"),
        "אוכל" to listOf("אוכל", "אכל", "אכילה"),
        "ברכתי" to listOf("ברכה", "מברך", "ברך", "ברכ"),
        "מברכים" to listOf("מברכ", "ברכה", "לברך", "ברכ"),
        "מברכ" to listOf("מברכ", "ברכה", "ברכ"),
        "לברך" to listOf("מברכ", "ברכה", "ברכ"),
        "ברכה" to listOf("ברכה", "ברכות", "לברכ", "מברכ"),
        "ברכת" to listOf("ברכת", "ברכות", "מברכ", "לברכ"),
        "מזונ" to listOf("מזונ", "מזון", "המזונ"),
        "שבת" to listOf("שבת", "השבת", "בשבת"),
        "סוכה" to listOf("סוכה", "סכה", "סוכות", "סוככ"),
        "סכה" to listOf("סוכה", "סוכות"),
        "סככ" to listOf("סכך", "סככ", "מסככ"),
        "גובה" to listOf("גבוה", "גובה", "גבוהה", "אמות"),
        "גבה" to listOf("גבוה", "גובה", "גבוהה"),
        "פסח" to listOf("פסח", "הפסח", "בחג"),
        "חמץ" to listOf("חמץ", "החמץ"),
        "מצה" to listOf("מצה", "מצות", "מצה"),
        "שופר" to listOf("שופר", "השופר", "תרועה", "לתקוע"),
        "תקיעה" to listOf("תקיעות", "תוקע", "תקע", "תרועה"),
        "תקיעות" to listOf("תקיעות", "תוקע", "תקע", "תרועה"),
        "תשובה" to listOf("תשובה", "לשוב", "שב", "לתשובה"),
        "חטא" to listOf("חטא", "חוטא", "עבירה"),
        "גזלה" to listOf("גזילה", "גזלה", "גזל"),
        "גזילה" to listOf("גזל", "גזילה", "גזלה"),
        "גנבה" to listOf("גנב", "גניבה", "גנבה"),
        "גניבה" to listOf("גנב", "גניבה", "גנבה"),
        "להחזיר" to listOf("השיב", "להשיב", "חוזר", "החזרה"),
        "חייב" to listOf("חיב", "חייב", "מצוה", "מצווה"),
        "חובה" to listOf("חיב", "חובה", "מצוה", "מצווה"),
        "לשון" to listOf("לשונ", "לשון"),
        "רכילות" to listOf("רכילות", "רכיל", "מרגל"),
        "צדקה" to listOf("צדקה", "לצדקה", "עניימ"),
        "עניים" to listOf("עניי", "עני", "עניימ"),
        "ללמוד" to listOf("למוד", "ללמוד", "תלמוד", "למד"),
        "ללמד" to listOf("ללמד", "ללמוד", "למד"),
        "רוצה" to listOf("רוצה", "רצונו", "ירצה"),
        "פעמימ" to listOf("פעמימ", "פעמים", "פעמי"),
        "כמה" to listOf("כמה", "מספר", "מנינ", "שיעור"),
        "לפני" to listOf("לפני", "קודמ", "קודם", "תחלה"),
        "אחרי" to listOf("אחרי", "לאחר", "אחר", "משגמר"),
        "ספק" to listOf("ספק", "שמא", "מסופק", "חשש"),
        "סכנה" to listOf("סכנה", "סכנת", "נפשות", "פיקוח"),
        "מלאכה" to listOf("מלאכה", "מלאכות", "מלאכת", "עבודה"),
        "עבודה" to listOf("עבודה", "מלאכה", "מלאכת"),
        "פצוע" to listOf("פצוע", "חולה", "מכה", "חולי"),
        "חולה" to listOf("חולה", "חולי", "חולימ", "פצוע"),
        "תשלום" to listOf("לשלמ", "משלמ", "תשלומ", "חיב"),
        "לשלמ" to listOf("משלמ", "לשלמ", "תשלומ"),
        "השבת" to listOf("להשיב", "השבה", "להחזיר", "שבת")
    )
    private val intentTime = setOf("מתי", "באיזו", "באיזה", "שעה", "זמן", "עד", "אימתי", "זמנ")
    private val intentWhere = setOf("איפה", "היכנ", "היכן", "מקומ", "מקום", "היכן")
    private val intentCount = setOf("כמה", "מספר", "מנינ", "שיעור", "פעמימ", "גבוה", "גובה")
    private val intentRequire = setOf("חייב", "חובה", "מצווה", "מצות", "צריך", "רשאי", "מותר", "אסור")
    private val intentCondition = setOf("אם", "כאשר", "למרות", "אפילו", "במקרה", "ספק", "שכחתי", "שכח")

    fun analyze(question: String): Analysis {
        val normalized=HebrewSearch.normalize(question.replace("ר״ח", "ראש חודש").replace("ר\"ח", "ראש חודש"))
        val all=normalized.split(' ').filter { it.isNotBlank() }
        val intent=when {
            all.any { it in intentCount } -> Intent.HOW_MANY
            all.any { it in intentTime } -> Intent.WHEN
            all.any { it in intentWhere } -> Intent.WHERE
            all.any { it in intentCondition } -> Intent.CONDITION
            all.any { it in intentRequire } -> Intent.REQUIREMENT
            else -> Intent.GENERAL
        }
        val result=ArrayList<Term>()
        val seen=HashSet<String>()
        for (word in all) {
            if (word.length<3 || word in stop) continue
            val alternatives=(synonyms[word].orEmpty()+word).map { HebrewSearch.normalize(it) }
                .filter { it.length>=3 && !it.contains(' ') }.distinct().take(8)
            val stem=stem(word)
            if (!seen.add(stem)) continue
            val intentWord = word in intentTime || word in intentWhere || word in intentCount || word in intentRequire
            result.add(Term(word,alternatives,if (intentWord) .48f else 1f))
            if (result.size>=11) break
        }
        return Analysis(result,intent,normalized)
    }

    private val affixes = charArrayOf('ב','כ','ל','מ','ו','ה','ש')
    /** Conservative morphology: no arbitrary 3-letter root conflation. */
    fun stem(s: String): String {
        var w=HebrewSearch.normalize(s)
        if (w.length>5 && w.firstOrNull() in affixes) w=w.substring(1)
        if (w.length>5 && w.firstOrNull() in affixes && s.startsWith("ו")) w=w.substring(1)
        if (w.length>5 && (w.endsWith("ימ") || w.endsWith("ות"))) w=w.dropLast(2)
        else if (w.length>5 && (w.endsWith("יה") || w.endsWith("ה"))) w=w.dropLast(1)
        return w
    }

    fun matches(word: String, candidate: String): Float {
        if (word == candidate) return 1f
        if (word.length>=4 && candidate.startsWith(word)) return .91f
        if (candidate.length>=4 && word.startsWith(candidate) && (word.length-candidate.length)<=2) return .85f
        val a=stem(word); val b=stem(candidate)
        if (a.length>=4 && a==b) return .84f
        if (a.length>=4 && b.length>=4 && (a.startsWith(b) || b.startsWith(a)) && kotlin.math.abs(a.length-b.length)<=2) return .67f
        return 0f
    }

    fun intentBoost(intent: Intent, normalizedPassage: String): Float = when(intent) {
        Intent.WHEN -> if (Regex("שעה|זמנ|משתשקע|שתשקע|שקיע|לילה|בוקר|שחר|ביומ|עד|תחלה|קודמ|בערב").containsMatchIn(normalizedPassage)) 8f else 0f
        Intent.WHERE -> if (Regex("מקומ|פתח|צד|ימינ|שמאל|באמצע|בית|עיר|ארצ|מעל|תחת|רחוק|אצל").containsMatchIn(normalizedPassage)) 6f else 0f
        Intent.HOW_MANY -> if (Regex("אחד|שתימ|שלוש|שלשה|ארבע|חמש|שש|שבע|שמונה|תשע|עשר|עשרימ|כמה|מספר|אמות|כזית|רביעית").containsMatchIn(normalizedPassage)) 8f else 0f
        Intent.REQUIREMENT -> if (Regex("חיב|חייב|מצוה|מצות|אסור|מותר|פטור|צריך|אינו|לא ").containsMatchIn(normalizedPassage)) 4f else 0f
        Intent.CONDITION -> if (Regex("אם |אבל|אלא|ספק|שכח|נזכר|ואפ|אינו|לא ").containsMatchIn(normalizedPassage)) 5f else 0f
        else -> 0f
    }
}
