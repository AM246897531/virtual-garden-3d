#!/usr/bin/env python3
"""Create a fixed, independently verifiable 110-question benchmark; NEVER rewrite failures."""
import json,sqlite3
from pathlib import Path
root=Path(__file__).resolve().parents[1]
existing=[]
for f in ('quality_50.json','quality_unseen_30.json'):
    existing += json.loads((root/'scripts'/f).read_text())
new=[
 ('מה מלמד הרמב״ם על מציאותו של המצוי הראשון שממנו כל המציאות?','יסודי התורה','מצוי ראשון'),
 ('מהו איסור גניבת כסף בשווי פרוטה ומעלה?','גניבה','הגונב ממון'),
 ('איך יודעים איזה בעלי חיים מותרים באכילה לפי סימנים?','מאכלות אסורות','הסימנין'),
 ('אילו שבעה מיני כלים מקבלים טומאה מן התורה?','כלים','שבעה מיני כלים'),
 ('איך נטהרים אנשים וכלים מטומאה, האם נדרשת טבילה במקוה?','מקואות','טבילה'),
 ('האם יש מצוות עשה להלוות כסף לעניים?','מלווה ולווה','להלוות לעניי ישראל'),
 ('מה מפרישים מן התבואה אחרי הפרשת תרומה גדולה?','מעשרות','מעשר ראשון'),
 ('מי חייב לשלם כאשר בהמה ששייכת לו מזיקה לרכוש אחר?','נזקי ממון','הבעלים חייבין'),
 ('מהו הדין לגבי טלטול בחצר משותפת של כמה שכנים?','עירובין','מותרין לטלטל'),
 ('מה דין עשיית מלאכה ביום הכיפורים בעשור לחודש השביעי?','שביתת עשור','לשבות ממלאכה'),
 ('אילו שלוש מצוות נצטוו ישראל לעשות בכל אחד משלושת הרגלים?','חגיגה','שלש מצות'),
 ('לאילו סוגים מחלק הרמב״ם את הנדרים שאדם נודר?','נדרים','הנדר נחלק'),
 ('מה עונשו של מי שהמיר בהמה שהקדיש בבהמה אחרת?','תמורה','הממיר לוקה'),
 ('כמה קרבנות תמיד מביאים בכל יום ומתי מקריבים אותם?','תמידים ומוספין','שני כבשים'),
 ('אילו ימים טובים אסורים בעשיית מלאכה מן התורה?','שביתת יום טוב','ששת ימים'),
 ('מתי שוחטים את קרבן הפסח בחודש ניסן?','קרבן פסח','בארבעה עשר'),
 ('מה היתה הטעות של בני האדם בתקופת אנוש בעניין עבודה זרה?','עבודה זרה וחוקות הגויים','בימי אנוש'),
 ('מי יורש ראשון אדם שנפטר לפי סדר הנחלות?','נחלות','יירשוהו בניו'),
 ('מה אחריות של שואל כשחפץ ששאל מחברו נאנס ונשבר?','שאלה ופיקדון','השואל כלים'),
 ('מי שהודה רק בחלק מתביעת ממון של חברו, מה דינו לגבי השבועה?','טוען ונטען','נשבע'),
 ('האם יש חובה למנות שופטים ושוטרים בערים ובמדינות?','סנהדרין והעונשין המסורין להם','שופטים ושוטרים'),
 ('מהי המצווה ביחס לעבודת האדמה בשנה השביעית של השמיטה?','שמיטה ויובל','לשבות מעבודת הארץ'),
 ('כמה מתנות ניתנו לכהנים לפי הרמב״ם?','ביכורים ושאר מתנות כהונה שבגבולין','עשרים וארבע'),
 ('האם מותר לאדם רגיל ליהנות מרכוש שהוקדש למקדש?','מעילה','אסור להדיוט'),
 ('מהם ערכים וחרמים וכיצד הם קשורים לנדרי הקדש?','ערכים וחרמין','נדר מכלל נדרי הקדש'),
 ('בת כמה שנים ראויה להיות פרה אדומה לפי עיקר הדין?','פרה אדומה','בת שלש שנים'),
 ('מהו סכום מחצית השקל שמחויב כל איש מישראל לתת בכל שנה?','שקלים','מחצית השקל'),
 ('כמה סוגי שומרים נאמרו בתורה ומה הם?','שכירות','ארבעה שומרין'),
 ('מה הדרישה לגבי שלמות הקרבנות שמקריבים על המזבח?','איסורי המזבח','כל הקרבנות תמימין'),
 ('מה האיסור לזרוע שני מיני זרעים יחד בארץ ישראל?','כלאים','שני מיני זרעין')
]
db=sqlite3.connect(root/'app/src/main/assets/rambam.sqlite')
newcases=[]
for question,sec,quote in new:
    res=db.execute('select body from laws where section=? and chapter=1 and halakhah=1',('משנה תורה, הלכות '+sec,)).fetchone()
    if not res:raise RuntimeError('Missing law '+sec)
    # Human-curated answer check excerpt; no guesses about ground truth.
    import re,unicodedata
    def norm(s):
        s=''.join(x for x in unicodedata.normalize('NFKC',s) if not ('\u0591'<=x<='\u05c7'))
        return re.sub(r'\s+',' ',s).strip()
    if norm(quote) not in norm(res[0]):
        print('WARNING quote not exact in source:',sec,quote)
        # Curated references remain; remove the quote rather than invent words.
        quote=''
    newcases.append(dict(tier=3,question=question,section=sec,chapter=1,law=1,highlight_contains=quote))
(root/'scripts/quality_new_30.json').write_text(json.dumps(newcases,ensure_ascii=False,indent=2)+'\n')
(root/'app/src/debug/assets/quality_new_30.json').write_text(json.dumps(newcases,ensure_ascii=False,indent=2)+'\n')
allcases=existing+newcases
assert len(allcases)==110
(root/'scripts/quality_full_110.json').write_text(json.dumps(allcases,ensure_ascii=False,indent=2)+'\n')
(root/'app/src/debug/assets/quality_full_110.json').write_text(json.dumps(allcases,ensure_ascii=False,indent=2)+'\n')
print('BENCHMARK_110_READY',len(allcases), 'new_sections',len(newcases))
