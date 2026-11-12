#!/usr/bin/env python3
"""560 semantically invariant, adversarial wrappers around 140 manually worded QA.

Test-only corpus. These are NOT independent free-form questions and the
original gold references are reused only for verification, never retrieval.
Every perturbation preserves the actual legal fact pattern unchanged.
"""
from __future__ import annotations
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
OLD=(ROOT/"scripts/quality_natural_100.json",
     ROOT/"scripts/quality_holdout_nonopening_40.json")
OUTPUT=ROOT/"artifacts/quality_560_wrapper_stress.json"
DEBUG=ROOT/"app/src/debug/assets/quality_560_wrapper_stress.json"
WRAPPERS=[
    ("יש לי בקשה למצוא מראה מקום במשנה תורה לרמב״ם. "
     "אין צורך להוסיף מידע שאינו כתוב במקור. {q}"),
    ("{q} בבקשה תן לי את המקור ההלכתי המדויק ברמב״ם ולא סיכום כללי."),
    ("אני לומד את משנה תורה ורוצה לוודא את לשון המקור. "
     "אינני מבקש פרשנות עצמאית. השאלה הספציפית שלי היא: {q} "
     "תציג את ההלכה שממנה לומדים זאת."),
    ("לצורך עיון בטקסט תורני וללא הסתמכות על תשובה משוערת, "
     "אני מבקש תשובה מבוססת מקור בלבד. {q} "
     "אם קיימים כמה מקורות דומים, תן עדיפות למקור שממש עונה לשאלה."),
]

def main():
    base=[item for path in OLD for item in json.loads(path.read_text(encoding="utf-8"))]
    assert len(base)==140 and len({x["question"] for x in base})==140
    cases=[]
    for variant,pattern in enumerate(WRAPPERS):
        for original_index,item in enumerate(base):
            value=dict(item)
            value["question"]=pattern.format(q=item["question"])
            value["origin"]="metamorphic-wrapper-of-manual-question"
            value["parent_question_index"]=original_index+1
            value["variant"]=variant+1
            value["independent_free_paraphrase"]=False
            value["legal_fact_pattern_changed"]=False
            cases.append(value)
    assert len(cases)==560
    assert len({x["question"] for x in cases})==560
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    DEBUG.parent.mkdir(parents=True,exist_ok=True)
    data=json.dumps(cases,ensure_ascii=False,indent=2)+"\n"
    OUTPUT.write_text(data,encoding="utf-8")
    DEBUG.write_text(data,encoding="utf-8")
    print("INVARIANT_WRAPPER_Q560_CREATED variants=4 original_human_questions=140 "
          "total=560 source_labels_reused=560 independent_new_questions=0",flush=True)

if __name__=="__main__":main()
