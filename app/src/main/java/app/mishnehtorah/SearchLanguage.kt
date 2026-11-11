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
        "דבר", "כזה", "בכלל", "למה", "מדוע", "זה", "זאת", "הוא", "היא", "מצב", "צריך",
        "איפה", "היכן", "כיצד", "איך", "מתי", "אימתי", "והאם", "אז", "כאן", "מהו", "מהי",
        "איזה", "ואיזה", "אילו", "אילן", "באיזה", "באיזו", "כמה אנשים", "כדי", "עבור",
        "ממנה", "ממנו", "לכך", "לעצמו", "לעצמי", "באמת", "במקרה", "כאשר",
        "יעשה", "עושים", "עושה", "עושים", "מהלך", "כלומר", "מתכוון", "במצב", "אנא",
        "קורה", "כבר", "ומה", "אפילו", "והאם", "צריכים", "צריכימ", "כדי", "רק",
        "להשתמש", "שימוש", "למי", "באמצעות", "השתמש", "בעזרת"
    ).map { HebrewSearch.normalize(it) }.toSet()
    // Every mapping captures a synonym or spelling difference, never a predetermined source.
    private val synonyms: Map<String, List<String>> = mapOf(
        "שכח" to listOf("שכח", "שכחתי", "שכחה", "נזכר", "הזכיר", "הזכרה"),
        "שכחתי" to listOf("שכח", "שכחה", "נזכר", "הזכיר"),
        "הזכיר" to listOf("הזכיר", "הזכרה", "שכח", "נזכר"),
        "ראש" to listOf("ראש", "ראשי"),
        "חודש" to listOf("החדש", "חדש", "חדשים", "חודש", "חודשים"),
        "חודשים" to listOf("חדש", "חדשים", "חודש"),
        "חדש" to listOf("החדש", "חדש", "חדשים", "חודש"),
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
        "אכילה" to listOf("אכל", "לאכל", "אוכל", "אכילה", "לאכול"),
        "לאכול" to listOf("אכל", "לאכל", "אוכל", "אכילה", "לאכול"),
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
        "חייבים" to listOf("חיב", "חייב", "חייבים", "חובה"),
        "קוראים" to listOf("קורא", "קוראין", "לקרות", "קריאה", "קריאת"),
        "לקרוא" to listOf("קורא", "קוראין", "לקרות", "קריאה", "קריאת"),
        "מזיזים" to listOf("משנין", "לשנות", "ממקומו", "מקום"),
        "חובה" to listOf("חיב", "חובה", "מצוה", "מצווה"),
        "לשון" to listOf("לשונ", "לשון"),
        "רכילות" to listOf("רכילות", "רכיל", "מרגל"),
        "צדקה" to listOf("צדקה", "לצדקה", "עניימ"),
        "עניים" to listOf("עניי", "עני", "עניימ"),
        "ללמוד" to listOf("למוד", "ללמוד", "תלמוד", "למד"),
        "ללמד" to listOf("ללמד", "ללמוד", "למד"),
        "רוצה" to listOf("רוצה", "רצונו", "ירצה"),
        "פעמימ" to listOf("פעמימ", "פעמים", "פעמי", "פעמיים", "פעמ"),
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
        "השתנתה" to listOf("נשתנית", "נשתנה", "משתנה", "שינוי", "שנתנה"),
        "השתנה" to listOf("נשתנית", "נשתנה", "משתנה", "שינוי"),
        "התייאשו" to listOf("נתיאשו", "ייאוש", "יאוש", "נתייאשו"),
        "התייאש" to listOf("נתייאש", "יאוש", "ייאוש"),
        "קטורת" to listOf("הקטרת", "קטרת", "קטורת"),
        "סממנים" to listOf("סממניה", "סממנ", "מסממניה", "סמימ", "סממני"),
        "מכינים" to listOf("נעשית", "עשיתו", "עשיית", "עושינ", "מכין"),
        "מכין" to listOf("נעשית", "עשיתו", "עשיית", "עושינ"),
        "לשבת" to listOf("יושב", "יושבינ", "ישיבה", "לשבת", "כיסא"),
        "כיסא" to listOf("כסאו", "כיסאו", "כסא", "יושבינ"),
        "כיסאו" to listOf("כסאו", "כיסאו", "כסא", "יושבינ"),
        "לרכוב" to listOf("רוכב", "רוכבינ", "רכיבה", "רכוב"),
        "סוסו" to listOf("סוסו", "סוס", "סוסימ"),
        "דרישות" to listOf("דרישה", "דרישות", "דרישת"),
        "חקירות" to listOf("חקירה", "חקירות", "חקירת"),
        "בדיקות" to listOf("בדיקה", "בדיקות", "בדיקת"),
        "חקירה" to listOf("חקירה", "חקירות", "חקירת"),
        "להעיד" to listOf("להעיד", "מעיד", "מעידינ", "עדות", "עדים", "העיד"),
        "עדים" to listOf("עדימ", "עדות", "מעיד", "להעיד"),
        "שחיטה" to listOf("שחט", "שחיטה", "לשחוט", "שוחט"),
        "לשחוט" to listOf("שחט", "שחיטה", "לשחוט", "שוחט", "שוחטינ"),
        "בהמה" to listOf("בהמה", "בהמת", "בהמות"),
        "בהמת" to listOf("בהמה", "בהמת", "בהמות"),
        "חולין" to listOf("חלינ", "חולינ", "החלינ", "חולין"),
        "עזרה" to listOf("עזרה", "בעזרה", "העזרה"),
        "יהודי" to listOf("יהודי", "ישראל"),
        "גוי" to listOf("עכומ", "גוי", "נכרי", "עובד כוכבימ"),
        "שליח" to listOf("שליח", "שלוח", "שלוחין", "שליחות"),
        "להתענות" to listOf("מתענינ", "מתענים", "תענית", "מתענית", "צומינ"),
        "ולהתריע" to listOf("מתריעינ", "מתריע", "להריע", "תוקעינ"),
        "להתריע" to listOf("מתריעינ", "מתריע", "להריע", "תוקעינ"),
        "צרות" to listOf("צרה", "צרות", "הצרות"),
        "ציבור" to listOf("צבור", "הצבור", "ציבור"),
        "בחבלה" to listOf("חבלה", "החובל", "חובל", "בחברו"),
        "מזיק" to listOf("המזיק", "מזיקינ", "מזיק", "חובל"),
        "ריפוי" to listOf("רפוי", "ריפוי", "לרפאות", "הרפואה"),
        "בושת" to listOf("בשת", "בושת", "הבשת"),
        "לשלם" to listOf("לשלמ", "משלמ", "תשלומ"),
        "להניח" to listOf("מניח", "מניחינ", "הנחה", "מניחו"),
        "הנחת" to listOf("מניח", "מניחינ", "הנחה", "מניחו"),
        "מקום" to listOf("מקומ", "מקומו", "מקום", "היכנ"),
        "מקומות" to listOf("מקומ", "מקומו", "מקום"),
        "סדר" to listOf("סדר", "למפרע", "כסדרנ", "סדור"),
        "המגילה" to listOf("מגילה", "מגלה", "קורא", "קוראין"),
        "מגילה" to listOf("מגלה", "מגילה", "למפרע"),
        "נקראת" to listOf("קורא", "קוראינ", "קרא", "לקרות", "קריאה"),
        "וידוי" to listOf("וידוי", "להתודות", "מתודה", "להודות"),
        "מתוודים" to listOf("מתודה", "להתודות", "ודוי", "וידוי"),
        "להתוודות" to listOf("להתודות", "מתודה", "וידוי", "ודוי"),
        "חזרה" to listOf("שב", "תשובה", "לשוב", "חזרה"),
        "שכח" to listOf("שכח", "שכחתי", "שכחה", "נזכר", "הזכיר", "הזכרה"),
        "רשאי" to listOf("כשר", "כשרינ", "הכל", "רשאי", "יכול"),
        "סכין" to listOf("סכינ", "בסכינ", "סכין", "ברזל", "צור"),
        "מתנת" to listOf("מתנה", "מתנות", "המתנות", "נתנינ"),
        "נתנו" to listOf("נתנ", "נתן", "נתנו", "נתינ"),
        "כיפר" to listOf("כפר", "כיפר", "מכפר", "כפרה"),
        "אנשים" to listOf("אנשימ", "אנשים", "האנשימ"),
        "להרוג" to listOf("הרג", "הורג", "ההורג", "נהרג"),
        "הרג" to listOf("הרג", "הורג", "ההורג", "נהרג"),
        "חרב" to listOf("סיפ", "סייף", "בחרב", "חרב"),
        "בחניקה" to listOf("חנקו", "חונק", "חניקה", "חנק"),
        "חניקה" to listOf("חנקו", "חונק", "חניקה", "חנק"),
        "בידיו" to listOf("בידו", "בידיו", "בעצמו"),
        "שענה" to listOf("ענה", "עונה", "שענה", "והעונה"),
        "שהשביע" to listOf("המשבע", "משביע", "השביעו", "השביע"),
        "עשרים" to listOf("עשרימ", "מעשרימ", "עשרים"),
        "קובעים" to listOf("שעור", "שיעור", "גבהה", "קובעים"),
        "גובה" to listOf("גובה", "גבהה", "גבוה", "אמות", "שעור"),
        "קידוש" to listOf("קידוש", "לקדש", "יקדש", "שיקדש", "מקדש", "משקדש", "קדש"),
        "הגזלן" to listOf("הגזלנ", "גזלנ", "הגזלה", "גזלה"),
        "שגזל" to listOf("גזל", "גזלה", "הגזלה", "גזלנ"),
        "בעלים" to listOf("בעלימ", "הבעלימ", "בעליו", "הבעלים"),
        "להחזיר" to listOf("חוזרת", "מחזיר", "להשיב", "חוזר", "השיב", "החזרה"),
        "אבידה" to listOf("אבדה", "אבידה", "מציאה", "מצא"),
        "מילה" to listOf("מלינ", "למול", "ימול", "מילה", "מהול"),
        "ברית" to listOf("למול", "מלינ", "ימול", "ברית", "מילה"),
        "עונש" to listOf("כרת", "לוקה", "מלקות", "סקילה", "מות", "חייב", "ענש", "עונש"),
        "שאכל" to listOf("האוכל", "אכל", "אוכל", "שאכל", "לאכל"),
        "ליל" to listOf("ליל", "הלילה", "בערב", "משקדש")
    ).mapKeys { HebrewSearch.normalize(it.key) }
        .mapValues { (_, words) -> words.map { HebrewSearch.normalize(it) } }
    private val intentTime = setOf("מתי", "באיזו", "באיזה", "שעה", "זמן", "עד", "אימתי", "זמנ").map { HebrewSearch.normalize(it) }.toSet()
    private val intentWhere = setOf("איפה", "היכנ", "היכן", "מקומ", "מקום").map { HebrewSearch.normalize(it) }.toSet()
    private val intentCount = setOf("כמה", "מספר", "מנינ", "שיעור", "פעמימ", "גבוה", "גובה").map { HebrewSearch.normalize(it) }.toSet()
    private val intentRequire = setOf("חייב", "חובה", "מצווה", "מצות", "צריך", "רשאי", "מותר", "אסור").map { HebrewSearch.normalize(it) }.toSet()
    private val intentCondition = setOf("אם", "כאשר", "למרות", "אפילו", "במקרה", "ספק", "שכחתי", "שכח").map { HebrewSearch.normalize(it) }.toSet()

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
        val nonJewish=normalized.contains("אינו יהודי") || normalized.contains("לא יהודי")
        val result=ArrayList<Term>()
        val seen=HashSet<String>()
        for (word in all) {
            if (nonJewish && (word=="יהודי" || word=="אינו")) continue
            if (word.length<3 || word in stop) continue
            var lexicalKey=word
            if (!synonyms.containsKey(lexicalKey)) {
                var without=word
                repeat(3) {
                    if (without.length>=4 && without.first() in charArrayOf('ב','ה','ו','ל','מ','ש','כ')) {
                        without=without.drop(1)
                        if (synonyms.containsKey(without)) lexicalKey=without
                    }
                }
            }
            // The literal user word must be the FIRST retrieval option;
            // wide synonym lists used to truncate it out of FTS candidate search.
            val alternatives=(listOf(word,lexicalKey)+synonyms[lexicalKey].orEmpty())
                .map { HebrewSearch.normalize(it) }.filter { it.length>=3 && !it.contains(' ') }
                .distinct().take(10)
            val stem=stem(word)
            if (!seen.add(stem)) continue
            val intentWord = word in intentTime || word in intentWhere || word in intentCount || word in intentRequire
            result.add(Term(word,alternatives,if (intentWord) .48f else 1f))
            if (result.size>=11) break
        }
        if (nonJewish && result.size<11) result.add(
            Term("עכומ",listOf("עכומ","נכרי","גוי","עובדכוכבימ"),1.2f))
        return Analysis(result,intent,normalized)
    }

    private val affixes = charArrayOf('ב','כ','ל','מ','ו','ה','ש')
    /** Conservative morphology: no arbitrary 3-letter root conflation. */
    fun stem(s: String): String {
        var w=s
        if (w.length>5 && w[0] in affixes) w=w.substring(1)
        if (w.length>5 && w[0] in affixes && s.startsWith("ו")) w=w.substring(1)
        if (w.length>5 && (w.endsWith("ימ") || w.endsWith("ות"))) w=w.dropLast(2)
        else if (w.length>5 && (w.endsWith("יה") || w.endsWith("ה"))) w=w.dropLast(1)
        return w
    }

    /** Allow matres lectionis and common Hebrew prefixes/suffixes, not random roots. */
    fun skeleton(original: String): String {
        var word=original
        // A prefix is dropped only if enough of the lexical root remains.
        var removed=0
        while (word.length>=5 && removed<2 && word.first() in charArrayOf('ב','כ','ל','מ','ו','ה','ש')) {
            word=word.drop(1);removed++
        }
        if (word.length>=5 && (word.endsWith("ימ") || word.endsWith("ות"))) word=word.dropLast(2)
        else if (word.length>=5 && (word.endsWith("יה") || word.endsWith("יו"))) word=word.dropLast(2)
        else if (word.length>=5 && word.endsWith('ה')) word=word.dropLast(1)
        // Vav and yod are often omitted in traditional spelling. Remove only
        // *interior* matres; a 3-letter skeleton is needed for fuzzy evidence.
        if (word.length>=5) word=word.first()+word.substring(1,word.lastIndex)
            .filter { it!='ו' && it!='י' }+word.last()
        return word
    }

    fun matches(word: String, candidate: String): Float {
        if (word == candidate) return 1f
        if (word.length>=4 && candidate.startsWith(word)) return .91f
        if (candidate.length>=4 && word.startsWith(candidate) && (word.length-candidate.length)<=2) return .85f
        val a=stem(word); val b=stem(candidate)
        if (a.length>=4 && a==b) return .84f
        if (a.length>=4 && b.length>=4 && (a.startsWith(b) || b.startsWith(a)) && kotlin.math.abs(a.length-b.length)<=2) return .67f
        val sa=skeleton(word);val sb=skeleton(candidate)
        if (sa.length>=3 && sa==sb) return .58f
        return 0f
    }

    fun intentBoost(intent: Intent, normalizedPassage: String): Float = when(intent) {
        Intent.WHEN -> if (Regex("שעה|זמנ|משתשקע|שתשקע|שקיע|לילה|בוקר|שחר|ביומ|עד|תחלה|קודמ|בערב").containsMatchIn(normalizedPassage)) 8f else 0f
        Intent.WHERE -> if (Regex("מקומ|פתח|צד|ימינ|שמאל|באמצע|בית|עיר|ארצ|מעל|תחת|רחוק|אצל").containsMatchIn(normalizedPassage)) 6f else 0f
        Intent.HOW_MANY -> if (Regex("אחד|אחת|שני|שנימ|שתימ|שלוש|שלשה|ארבע|חמש|שש|שבע|שמונה|תשע|עשר|עשרימ|כמה|מספר|אמות|כזית|רביעית").containsMatchIn(normalizedPassage)) 8f else 0f
        Intent.REQUIREMENT -> if (Regex("חיב|חייב|מצוה|מצות|אסור|מותר|מתר|פטור|צריך|אינו|לא ").containsMatchIn(normalizedPassage)) 4f else 0f
        Intent.CONDITION -> if (Regex("אם |אבל|אלא|ספק|שכח|נזכר|ואפ|אינו|לא ").containsMatchIn(normalizedPassage)) 5f else 0f
        else -> 0f
    }
}
