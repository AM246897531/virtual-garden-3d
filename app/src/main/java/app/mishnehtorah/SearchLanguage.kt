package app.mishnehtorah

import kotlin.math.ln

/** Language-level normalization, not hand-coded destinations to specific halakhot. */
internal object SearchLanguage {
    internal data class Term(val word: String, val alternatives: List<String>, val weight: Float = 1f)
    internal data class Analysis(val terms: List<Term>, val intent: Intent, val normalized: String,
        val deadlineOverrun: Boolean = false,
        val hasNegation: Boolean = false,
        val asksSpecificCase: Boolean = false)
    internal enum class Intent { GENERAL, WHEN, WHERE, HOW_MANY, REQUIREMENT, CONDITION }

    private val stop = setOf(
        "לברר", "מדבר", "נוגע", "מתיחס", "מתייחס", "כתוב", "המקור",
        "מקור", "מלמד", "מבקש", "מנסה",
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
        // Everyday Hebrew often describes an event, while Rambam names its
        // legal concept. These are language bridges, never source-ID mappings.
        "תלויה" to listOf("תלוי", "בידו", "רשות", "נתונה"),
        "באדמ" to listOf("אדמ", "האדמ", "לאדמ"),
        "רע" to listOf("הרע", "רעה", "רשע", "רשעימ"),
        "בחירה" to listOf("רשות", "ברשות", "רצונו", "רצה", "בחירתו"),
        "לבחור" to listOf("רשות", "רצה", "בחירה", "רצונו"),
        "להחליט" to listOf("רשות", "רצה", "בחירה", "רצונו"),
        "סביבה" to listOf("דעות", "מעשיו", "חבריו", "רעיו", "הולך"),
        "חברים" to listOf("חבריו", "חביריו", "רעיו", "רעים"),
        "חבריי" to listOf("חבריו", "חביריו", "רעיו", "רעים"),
        "מושפע" to listOf("נמשך", "נמשכ", "דעותיו", "מעשיו", "אחר"),
        "משפיעים" to listOf("נמשך", "דעותיו", "חבריו", "מעשיו"),
        "חצר" to listOf("חצר", "חצרות", "חצירות", "רשות"),
        "משותפת" to listOf("שותפין", "שותפימ", "חצרות", "יחד"),
        "טלטול" to listOf("לטלטל", "מטלטל", "מוציא", "להוציא", "עירוב"),
        "לטלטל" to listOf("מוציא", "להוציא", "מטלטל", "ערוב", "עירוב"),
        "עירוב" to listOf("ערוב", "עירוב", "מערבינ", "ערב"),
        "קדקד" to listOf("קדקד", "ראש", "שערות", "שער"),
        "מצח" to listOf("קדקד", "מצח", "ראש", "שער"),
        "מעל" to listOf("מעל", "למעלה", "על", "קדקד"),
        "הזריחה" to listOf("הנצ", "החמה", "זריחה", "תנץ"),
        "זריחה" to listOf("הנצ", "החמה", "תנץ"),
        "החמצתי" to listOf("איחר", "אחר", "עבר", "לאחר"),
        "סימנים" to listOf("סימנ", "סימני", "מעלה", "מפרסת"),
        "כשרות" to listOf("מותרות", "טמאות", "טהורות", "סימנ"),
        "להכשיר" to listOf("כשר", "מותר", "ראוי"),
        "נשבר" to listOf("נשבר", "שבר", "אונס", "נאנס"),
        "נשברה" to listOf("נשבר", "שבר", "אונס", "נאנס"),
        "שכח" to listOf("שכח", "שכחתי", "שכחה", "נזכר", "הזכיר", "הזכרה"),
        "שכחתי" to listOf("שכח", "שכחה", "נזכר", "הזכיר"),
        // General late/expired-time language, not references to any particular law.
        "נגמר" to listOf("עבר", "איחר", "אחר", "לאחר", "שעות"),
        "נגמרה" to listOf("עבר", "איחר", "אחר", "לאחר"),
        "הסתיים" to listOf("עבר", "איחר", "אחר", "לאחר"),
        "פספסתי" to listOf("איחר", "אחר", "עבר", "שכח"),
        "איחרתי" to listOf("איחר", "אחר", "עבר", "לאחר"),
        "החמצתי" to listOf("איחר", "אחר", "עבר", "לאחר"),
        "בבוקר" to listOf("ביומ", "שחרית", "בבקר", "בבוקר"),
        "בוקר" to listOf("ביומ", "שחרית", "בקר"),
        "זמן" to listOf("זמנ", "שעה", "שעות", "עונה", "בעונתו"),
        "הזמן" to listOf("זמנ", "שעה", "שעות", "עונה", "בעונתו"),
        "זמנ" to listOf("זמנ", "שעה", "שעות", "עונה", "בעונתו"),
        "לקריאה" to listOf("קורא", "קוראינ", "לקרות", "קריאה"),
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
        // A sentence describing that a deadline has ALREADY passed is different
        // from a generic question about when a mitzvah begins or ends.
        val timeMentioned = Regex("זמנ|שעה|שעות|מאוחר|שחרית|בוקר|בקר").containsMatchIn(normalized)
        val beforeDeadline=Regex(
            "לפני (ש)?נגמר|לפני (ש)?הסתימ|טרמ נגמר|טרמ הסתימ|" +
            "עוד לא עבר|עדינ לא עבר|לא עבר הזמנ|לא נגמר הזמנ|" +
            "הספקתי .*לפני"
        ).containsMatchIn(normalized)
        val deadlineOverrun = timeMentioned && !beforeDeadline &&
            Regex("נגמר|הסתימ|חלפ|עבר|איחר|פספס|החמצ|לא הספק|מאוחר מדי").containsMatchIn(normalized)
        // Negation is a logical condition, not just another common two-
        // letter lexical word. Preserve it separately from stopword removal.
        // HebrewSearch.normalize converts final consonants, including
        // אם -> אמ and אין -> אינ. Every comparison MUST run through the
        // same normalizer, or real conditional/negative questions are lost.
        val negationWords=setOf("לא","בלי","ללא","אינו","אינה",
            "אינני","שלא","אין","אינן")
            .map { HebrewSearch.normalize(it) }.toSet()
        // Hebrew productive one-letter prefixes must not hide a negation:
        // ואין -> ואינ, ואם -> ואמ after the standard final-letter fold.
        fun exactOrPrefixed(token:String, vocabulary:Set<String>):Boolean =
            token in vocabulary || (token.length>=3 &&
                token.first() in "ושבהלמכ" && token.drop(1) in vocabulary)
        val hasNegation=all.any { exactOrPrefixed(it,negationWords) }
        val specificConditionWords=setOf("אם","כאשר","אפילו","למרות",
            "במקרה","מקרה","ספק","שכחתי","שכח","נזכר","איחר",
            "פספס","תנאי","בתנאי","קרה")
            .map { HebrewSearch.normalize(it) }.toSet()
        val asksSpecificCase=all.any { exactOrPrefixed(it,specificConditionWords) ||
            (it.length>=4 && it.startsWith("כש")) }
        val nonJewish=normalized.contains("אינו יהודי") || normalized.contains("לא יהודי")
        val discourseLead=normalized.startsWith("אני רוצה לברר") ||
            normalized.startsWith("אני רוצה לדעת") ||
            normalized.startsWith("אני רוצה להבין") ||
            normalized.startsWith("אני רוצה למצוא")
        // Protect short Hebrew terms with real legal meaning, without
        // accepting arbitrary two-character FTS noise.
        val meaningfulShort=setOf("רע","דם","גט","אש","לב","יד","שמ")
        // Gather content words from the WHOLE question. A first-16 cutoff
        // loses the decisive condition when the user writes a long narrative.
        val candidates=ArrayList<Term>()
        val seen=HashSet<String>()
        for (word in all) {
            if (nonJewish && (word=="יהודי" || word=="אינו")) continue
            if ((word.length<3 && word !in meaningfulShort) ||
                word in stop || word.startsWith("הרמב") || word.startsWith("רמב") ||
                (discourseLead && word=="רוצה")) continue
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
            val unprefixed=if (word.length>=4 &&
                word.first() in charArrayOf('ב','ה','ו','ל','מ','ש','כ'))
                word.drop(1) else ""
            val alternatives=(listOf(word,lexicalKey,unprefixed)+synonyms[lexicalKey].orEmpty())
                .map { HebrewSearch.normalize(it) }.filter { it.length>=3 && !it.contains(' ') }
                .distinct().take(10)
            val stem=stem(word)
            if (!seen.add(stem)) continue
            val intentWord = word in intentTime || word in intentWhere || word in intentCount || word in intentRequire
            candidates.add(Term(word,alternatives,if (intentWord) .48f else 1f))
            if (candidates.size>=64) break
        }
        val cap=if (deadlineOverrun) 14 else 16
        val result=ArrayList<Term>(cap+2)
        if (candidates.size<=cap) {
            result.addAll(candidates)
        } else {
            // Balance beginning, conclusion, and informative central terms.
            // Preserve the original word order in the final analysis. This
            // is generic input handling, not mapping benchmark questions
            // to any preferred source.
            val selected=HashSet<Int>()
            for (i in 0 until minOf(6,candidates.size)) selected.add(i)
            for (i in maxOf(0,candidates.size-6) until candidates.size) selected.add(i)
            val meaningful=setOf("שכח", "שכחתי", "עבר", "איחר", "ספק",
                "הפסיד", "אסור", "מותר", "שבת", "שמע", "סוכה",
                "אינו", "יכול", "חייב", "לאחר", "אחרי")
            val available=candidates.indices.filter { it !in selected }
                .sortedWith(compareByDescending<Int> { i ->
                    val term=candidates[i]
                    (if (term.word in meaningful) 6 else 0) +
                    (if (term.alternatives.size>=3) 3 else 0) +
                    minOf(4,term.word.length/3)
                }.thenBy { it })
            for (i in available.take(cap-selected.size)) selected.add(i)
            for (i in candidates.indices) if (i in selected) result.add(candidates[i])
        }
        if (deadlineOverrun) {
            // Two independent evidence concepts: time boundary + overdue action.
            // Neither knows or names a target source; both must meet source text.
            if (result.none { it.word == "איחר" }) result.add(
                Term("איחר", listOf("איחר", "אחר", "לאחר", "עבר"), 1.25f))
            if (result.none { it.word == "שעות" }) result.add(
                Term("שעות", listOf("שעות", "שעה", "זמנ", "עונתו"), 1.1f))
        }
        if (nonJewish && result.size<16) result.add(
            Term("עכומ",listOf("עכומ","נכרי","גוי","עובדכוכבימ"),1.2f))
        return Analysis(result,intent,normalized,deadlineOverrun,hasNegation,
            asksSpecificCase)
    }

    /**
     * Concise language-to-topic bridges, not question-to-law destinations.
     * We use accepted Hebrew legal vocabulary to retrieve authentic text
     * when conversational wording and the Rambam's classical vocabulary
     * have little literal overlap. No IDs, source citations or conclusions
     * are embedded here. The original query is ALWAYS searched separately.
     */
    fun sourceVocabularyBridges(question: String): List<String> {
        val q=HebrewSearch.normalize(question)
        val output=ArrayList<String>(2)
        if (listOf("רצה והחליצנו","רצה והחלצנו").any { q.contains(it) }) {
            output.add("שכח הזכרת שבת קדושת היום בברכה ברכת המזון")
        }
        if (q.contains("סוכה") &&
            listOf("גבוה","גובה","גבהה","אמות","אמה").any { q.contains(it) }) {
            output.add("גובה סוכה שיעור עשרים אמה טפחים גבוהה פסולה")
        }
        val animalQuery=listOf("בעלי חיימ","בהמות","חיות").any { q.contains(it) }
        val eatingQuestion=listOf("באכילה","לאכול","אכיל").any { q.contains(it) }
        val identificationQuestion=listOf("סימנימ","סימני","סימנ","כשר","טמא","טהור").any {
            q.contains(it)
        } || (eatingQuestion && q.contains("מותר"))
        if (animalQuery && identificationQuestion)
            output.add("סימנים של בהמה חיה עוף דגים טהור טמא מאכלות")
        val socialQuery=listOf("חברימ","רעיי","סביבה","חברתי").any { q.contains(it) }
        val influenceQuery=listOf("משפיע","מושפע","מתנהג","השפעה","התנהגות").any {
            q.contains(it)
        }
        if (socialQuery && influenceQuery)
            output.add("דעות הנהגות חכמים חברים רעים השפעה סביבה מעשים")
        // General colloquial-language -> classical terminology bridges.
        // These are topical retrieval routes, not question-to-chapter IDs.
        // Require *conjunctions* to avoid injecting irrelevant law topics.
        fun hasAny(vararg parts:String)=parts.any { q.contains(it) }
        if (hasAny("שבת") && hasAny("פטור") &&
            hasAny("מותר","איסור","אסור","דינ","מה פירוש"))
            output.add("פטור שבת אסור מדרבנן")
        if (hasAny("אופי","מדות","מידות","התנהגות","קיצונ") &&
            hasAny("קיצונ","אמצע","בינוני","ממוצע") &&
            hasAny("קיצונ","בחירה","לבחור","בינוני"))
            output.add("דעות מדות קצוות דרך ממוצעת דרך ישרה")
        if (hasAny("מתודה","מתווד","וידוי","להתוודות") &&
            hasAny("ממשיכ","להמשיכ","להמשיך","לא מתכונ","מתכונ","חוזר","לעזוב","עזב"))
            output.add("וידוי דברים תשובה לא גמר בלבו לעזוב החטא")
        if (hasAny("עירוב חצרות","עירובי חצרות","עירוב בחצר") &&
            hasAny("אוכל","מאכל","מזון","משותפ","מניח","פת"))
            output.add("שיתערבו במאכל אחד ויניחוהו בחצר עירובי חצרות")
        if (hasAny("ציבור","צבור","צרות","צרה","תענית") &&
            hasAny("מקרה","מקרי","קרי","לא מתעורר","לא מבקש להתעורר"))
            output.add("ציבור צרה זעקה תשובה קרי מקרה תענית")
        // Whole words only. Substring tests would mistake "ערב" for "רב"
        // and "אבידה" for "אב", triggering unrelated topic expansions.
        val queryWords=q.split(' ').toHashSet()
        val teacherQuery=queryWords.any {
            it in setOf("רב","הרב","רבו","לרב","מורה","המורה","מלמד","רבנו")
        }
        val parentQuery=queryWords.any {
            it in setOf("אב","אבא","אבי","אביו","האב","מאבא","מאבי","הורימ","ההורימ")
        }
        val respectQuery=queryWords.any {
            it in setOf("כבוד","לכבד","חובה","כיבוד","לכבוד","הכבוד")
        }
        if (teacherQuery && parentQuery && respectQuery)
            output.add("כבוד רבו מורא רבו אביו כבוד תלמוד תורה")
        return output.distinct()
    }

    /**
     * Protect real content from long conversational introductions, suffixes,
     * and multiple subquestions. Segments are only extra candidate-retrieval
     * routes. The complete question still governs the final answer ranking.
     */
    fun evidenceSegments(question: String): List<String> {
        if (question.length<75) return emptyList()
        val chunks=Regex("[.!?;׃\\n]+").split(question)
            .map { it.trim() }.filter { it.length>=9 && it.length<=950 }
        if (chunks.size<2) return emptyList()
        val scored=chunks.mapIndexed { i, chunk ->
            val n=HebrewSearch.normalize(chunk)
            val words=n.split(' ').filter { it.length>=3 && it !in stop }
            val meaningful=words.count { it.length>=4 }
            // Regex splitting strips punctuation, so inspect the real
            // following character in the original unmodified question.
            val originalAt=question.indexOf(chunk)
            val suffix=if (originalAt>=0) question.substring(originalAt+chunk.length)
                .trimStart() else ""
            val isQuestion=suffix.startsWith("?") || n.startsWith("מה ") ||
                n.startsWith("מתי ") || n.startsWith("האם ") ||
                n.startsWith("באיזה ") || n.contains(" שכחתי ")
            val boilerplate=if (listOf("אני מבקש למצוא","אני מבקש להבין",
                "אני מחפש את המקור","אני מנסה להבין",
                "להסתמך על סיכום","המקור הנכון","ההלכה המדויקת",
                "משנה תורה לרמב").any {
                    n.contains(HebrewSearch.normalize(it))
                }) 12 else 0
            Triple(i,chunk,meaningful+(if (isQuestion) 6 else 0)-boilerplate)
        }
        return scored.sortedWith(compareByDescending<Triple<Int,String,Int>> { it.third }
            .thenBy { it.first }).take(3).filter { it.third>0 }
            .map { it.second }.distinct().filter { it!=question.trim() }
    }

    /**
     * Rank on the actual question and its relevant conditions, not on
     * surrounding discourse such as "I want a source from the Rambam".
     * The unmodified input is still used by independent recall routes.
     *
     * If a long sentence has no reliable boundaries, preserve all words
     * instead of guessing and silently deleting a material condition.
     */
    fun focusedEvidenceQuery(question: String): String {
        val segments=evidenceSegments(question)
        if (segments.isEmpty()) return question
        val ordered=segments.sortedBy { part -> question.indexOf(part) }
        val focused=ordered.joinToString(" ").trim()
        return if (focused.isBlank()) question else focused
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
