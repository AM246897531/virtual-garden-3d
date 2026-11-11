#!/usr/bin/env python3
"""No-APK source-only checks of temporal intent and verbatim highlight offsets."""
import re, sqlite3
from pathlib import Path
from build_corpus import normalize

ROOT = Path(__file__).resolve().parents[1]
DB = ROOT / "app/src/main/assets/rambam.sqlite"

def overdue(q):
    q=normalize(q)
    return bool(
        re.search(r"זמנ|שעה|שעות|מאוחר|שחרית|בוקר|בקר",q)
        and not re.search(r"לפני (ש)?נגמר|לפני (ש)?הסתימ|טרמ נגמר|טרמ הסתימ|עוד לא עבר|עדינ לא עבר|לא עבר הזמנ|לא נגמר הזמנ|הספקתי .*לפני",q)
        and re.search(r"נגמר|הסתימ|חלפ|עבר|איחר|פספס|החמצ|לא הספק|מאוחר מדי",q)
    )

def late_source_excerpt(text):
    tokens=list(re.finditer(r"[\u0590-\u05ff]+",text))
    words=[normalize(t.group()) for t in tokens]
    for i,word in enumerate(words):
        if word not in ("אחר","איחר","לאחר","עבר"):continue
        if not any(x in ("שעות","שעה","זמנ","עונתו") for x in words[i:i+8]):continue
        for j in range(i,min(len(words)-1,i+32)):
            if (words[j],words[j+1]) not in (("לא","יצא"),("אינו","יוצא")):continue
            a=tokens[max(0,i-2)].start()
            b=tokens[min(len(words)-1,j+8)].end()
            if a<b and b-a<=340:return text[a:b]
    return None

def test():
    positive=[
        "שכחתי לקרוא קריאת שמע ונגמר הזמן של קריאת שמע בבוקר, מה עלי לעשות?",
        "יצאתי לקרוא שמע רק אחרי שכבר חלפו שלוש שעות מהבוקר",
        "איחרתי אחרי הזמן של קריאת שמע בבוקר",
        "פספסתי את הזמן לקריאת שמע בבוקר",
    ]
    negative=[
        "הספקתי לקרוא שמע לפני שנגמרו שלוש שעות מהבוקר",
        "מהו הזמן לקריאת שמע בבוקר?",
        "עוד לא עבר זמן קריאת שמע בבוקר",
        "הזמן לקריאת שמע בבוקר לא נגמר הזמן",
    ]
    assert all(overdue(q) for q in positive)
    assert all(not overdue(q) for q in negative)
    db=sqlite3.connect(DB)
    law=db.execute("SELECT body FROM laws WHERE section LIKE '%הלכות קריאת שמע' AND chapter=1 AND halakhah=13").fetchone()[0]
    excerpt=late_source_excerpt(law)
    assert excerpt is not None and len(excerpt)<=340
    assert normalize("לא יצא ידי חובת קריאת שמע") in normalize(excerpt)
    assert excerpt in law
    print(f"TEMPORAL_SOURCE_ONLY_TEST 8/8; span_chars={len(excerpt)}; android_accuracy=UNTESTED")

if __name__=="__main__":test()
