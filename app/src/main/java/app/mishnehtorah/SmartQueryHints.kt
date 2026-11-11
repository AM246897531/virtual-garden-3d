package app.mishnehtorah

/**
 * High-precision source navigation for a few common Hebrew question forms.
 * This is a deterministic ranking aid to the *local learned semantic model*,
 * not an answer generator. It only points to actual, bundled Rambam laws.
 */
internal data class SourceHint(
    val sectionName: String,
    val chapter: Int,
    val halakhah: Int,
    val reason: String
)

internal object SmartQueryHints {
    fun forQuestion(query: String): SourceHint? {
        val q = HebrewSearch.normalize(query)
        fun has(vararg pieces: String) = pieces.any { q.contains(HebrewSearch.normalize(it)) }
        val hasFoodBlessing = has("ברכת המזון", "ברכת מזון", "ברכתי על המזון", "בירכתי על האוכל")
        val forgot = has("שכח", "שכחתי", "לא אמר", "לא הזכר", "פספסתי", "דילגתי")
        val newMonth = has("ראש חודש", "ראש חדש", "ראשי חדשים", "ר ח")
        val yaaleh = has("יעלה ויבא", "יעלה ויבוא")
        if (hasFoodBlessing && (yaaleh || newMonth) && forgot)
            return SourceHint("הלכות ברכות", 2, 13, "יעלה ויבוא בברכת המזון")
        if (hasFoodBlessing && has("שבת", "יום טוב") && forgot)
            return SourceHint("הלכות ברכות", 2, 12, "הזכרת שבת בברכת המזון")
        if (has("קריאת שמע", "קוראים שמע", "לקרוא שמע") &&
            has("כמה פעמים", "פעמים ביום", "פעמיים", "כמה צריך"))
            return SourceHint("הלכות קריאת שמע", 1, 1, "מספר הקריאות ביום")
        if (has("קריאת שמע", "קוראים שמע", "לקרוא שמע") &&
            has("בלילה", "ערבית", "בערב", "זמן ערב"))
            return SourceHint("הלכות קריאת שמע", 1, 9, "זמן קריאת שמע בערב")
        if (has("קריאת שמע", "קוראים שמע", "לקרוא שמע") &&
            has("בבוקר", "בשחרית", "שחרית", "הנץ"))
            return SourceHint("הלכות קריאת שמע", 1, 11, "זמן קריאת שמע בבוקר")
        if (has("קידוש") && has("אוכל", "לאכול", "שתיה", "לשתות", "מותר לשתות", "טעימה") &&
            has("לפני", "קודם", "בלי", "טרם"))
            return SourceHint("הלכות שבת", 29, 5, "אכילה ושתיה לפני קידוש")
        if (has("חנוכה", "חנכה") && has("נרות", "נר", "חנוכיה") &&
            has("כמה", "מספר נרות", "כמה מדליקים"))
            return SourceHint("הלכות מגילה וחנוכה", 4, 1, "מספר נרות חנוכה")
        if (has("חנוכה", "חנכה") && has("להדליק", "מדליק", "נרות", "נר") &&
            has("מתי", "זמן", "שקיעה", "שוקעת"))
            return SourceHint("הלכות מגילה וחנוכה", 4, 5, "זמן הדלקת נרות חנוכה")
        if (has("לשון הרע", "לשון רע", "רכילות") &&
            has("מותר", "אסור", "מה הדין", "מה זה", "לספר", "לומר", "לדבר"))
            return SourceHint("הלכות דעות", 7, 2, "דיני לשון הרע")
        return null
    }
}
