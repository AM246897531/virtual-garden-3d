package app.mishnehtorah

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real Kotlin/JVM regressions against the production Hebrew query analyzer.
 * No APK, emulator, corpus downloads, gold law IDs or hardcoded answers.
 */
class HebrewQueryRegressionTest {
    @Test fun recognizesClassicalTopicAliasesWithoutDestinationIds() {
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "מה עושים אם שכחתי רצה והחליצנו בברכת המזון"
        ).any { it.contains("קדושת היום") })
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "כמה גבוה מותר לעשות סוכה באמות"
        ).any { it.contains("עשרים אמה") })
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "איך יודעים אם בהמות כשרות לאכילה לפי סימנים"
        ).any { it.contains("בהמה חיה עוף") })
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "מי שמתוודה אבל מתכוון להמשיך לחטוא"
        ).any { it.contains("וידוי דברים") })
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "האם אופי קיצוני יותר טוב מדרך האמצע"
        ).any { it.contains("דעות מדות") })
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "אם בהלכות שבת כתוב פטור האם זה מותר"
        ).any { it.contains("פטור שבת") })
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "עירוב חצרות עם אוכל משותף"
        ).any { it.contains("מאכל אחד") })
    }

    @Test fun refusesFalsePositiveVocabularyExpansions() {
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "מה חובת ערב שבת לגבי אבידה שנמצאה"
        ).isEmpty())
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "איך שומרים על סביבה נקייה מטינופת"
        ).isEmpty())
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "האם צריך לברך לפני אכילה של בעלי חיים"
        ).isEmpty())
        assertTrue(SearchLanguage.sourceVocabularyBridges(
            "מתי נפגשים עם המורה לטיול"
        ).isEmpty())
    }

    @Test fun keepsCriticalLateLegalConditions() {
        val question="אני מנסה להבין את הדין המקורי על מנת לברר איזו הלכה " +
            "קשורה למצב שתיארתי ולא לספר סיכום פופולרי. " +
            "מה עושים אם אדם שכח ברכת המזון ונזכר רק אחרי שעבר זמן רב?"
        val focused=SearchLanguage.focusedEvidenceQuery(question)
        val terms=SearchLanguage.analyze(focused).terms.map { it.word }
        assertTrue(terms.isNotEmpty())
        assertTrue(focused.contains("שכח"))
        assertTrue(focused.contains("אחרי"))
        assertTrue(focused.contains("זמן"))
    }

    @Test fun explicitNegationDoesNotDisappearWithStopwords() {
        val negated=SearchLanguage.analyze(
            "אם אדם לא קרא קריאת שמע ואיחר מה דינו")
        val positive=SearchLanguage.analyze(
            "אם אדם קרא קריאת שמע מה דינו")
        assertTrue(negated.hasNegation)
        assertFalse(positive.hasNegation)
        assertTrue(negated.asksSpecificCase)
        assertTrue(SearchLanguage.analyze(
            "אין לי אפשרות לקיים את המצווה").hasNegation)
        assertTrue(SearchLanguage.analyze(
            "אם שכחתי את הברכה מה הדין").asksSpecificCase)
        assertFalse(SearchLanguage.analyze(
            "מהם עיקרי הלכות שבת").asksSpecificCase)
        assertTrue(SearchLanguage.analyze(
            "ואם לא הספיק לקיים מה הדין").asksSpecificCase)
        assertTrue(SearchLanguage.analyze(
            "ואין לו אפשרות לברך").hasNegation)
        assertTrue(SearchLanguage.analyze(
            "איננו יכול לקרוא קריאת שמע").hasNegation)
        assertTrue(SearchLanguage.analyze(
            "אם אינו מברך מה הדין").hasNegation)
    }

    @Test fun fullOriginalQueryStaysUnmodifiedWithoutMultipleClauses() {
        val q="מהי דרך האופי הישרה לפי הרמבם?"
        assertTrue(SearchLanguage.focusedEvidenceQuery(q)==q)
    }
}
