#!/usr/bin/env python3
"""Build 500 traceable Rambam questions from authentic, local source text.

140 = the pre-existing 100 free-form questions and 40 manually authored
non-opening-law questions. The other 360 are reproducibly SOURCE-DERIVED
synthetic questions, NOT independently authored free paraphrases. The output
separates these categories for honest evaluation and MUST NEVER be fed into
production search as a lookup table. No Android build.
"""
from __future__ import annotations
import collections
import json
import random
import re
import sqlite3
from pathlib import Path
from build_corpus import normalize

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/"app/src/main/assets/rambam.sqlite"
MAIN=ROOT/"scripts/quality_natural_100.json"
HOLD=ROOT/"scripts/quality_holdout_nonopening_40.json"
OUTPUT=ROOT/"artifacts/quality_500_gold.json"
DEBUG=ROOT/"app/src/debug/assets/quality_500_gold.json"
REPORT=ROOT/"artifacts/quality_500_description.json"
EXCLUDE=("אישות","איסורי ביאה","גירושין","יבום וחליצה",
         "נערה בתולה","סוטה","מיאון","נדה")
INTRO=("והוא","ואם","וכיצד","במה","הלכה","אמר","שנאמר",
       "ואין","ואלו","הרי","כיון","אחד","דבר")
TEMPLATES=(
    "איפה הרמב״ם דן במצב הבא: {cue}? מה הדין והמקור?",
    "יש לי מקרה מעשי: {cue}. מה נקבע במשנה תורה?",
    "מה קובע הרמב״ם לגבי {cue}? אני צריך את המקור המדויק.",
    "אם המצב הוא כזה: {cue}, מה ההלכה הרלוונטית?",
    "מצא את הדין למקרה הזה ברמב״ם: {cue}.",
    "איפה מפורט ברמב״ם מה עושים כשיש מצב של {cue}?",
)

def source_excerpt(norm: str,variant:int) -> tuple[str,str] | None:
    # The first clauses are frequently definitions and can be indistinguishable
    # from neighboring laws; include middle and conditional clauses.
    segments=re.split(r"[.;׃:]\s*",norm)
    candidates=[]
    for part in segments:
        words=part.strip().split()
        if len(words)<14 or len(words)>110:continue
        for marker in ("אם","ואם","כיצד","כשהוא","שלא","אבל"):
            if marker in words:
                index=words.index(marker)
                if index+13<len(words):
                    candidates.append((words,index))
    if not candidates:
        words=norm.split()
        if len(words)<24:return None
        candidates=[(words,min(12+variant%7,len(words)-19))]
    words,index=candidates[variant%len(candidates)]
    # The test question quotes SOURCE wording; it is NOT a human paraphrase.
    focus=words[index:index+min(17,len(words)-index)]
    if len(focus)<10:return None
    cue=" ".join(focus)
    anchor=" ".join(focus[2:10])
    if not (36<=len(cue)<=205 and len(anchor)>=15):
        return None
    return cue,anchor

def make():
    original=json.loads(MAIN.read_text(encoding="utf-8"))
    reviewed=json.loads(HOLD.read_text(encoding="utf-8"))
    assert len(original)==100 and len(reviewed)==40
    db=sqlite3.connect(DB)
    manual=list(original)+list(reviewed)
    assert len({case["question"] for case in manual})==140
    already={(case["section"],case["chapter"],case["law"])
             for case in manual}
    groups=collections.defaultdict(list)
    for lawid,section,chapter,law,norm in db.execute(
        "SELECT id,section,chapter,halakhah,norm FROM laws ORDER BY id"
    ):
        if law<=1 or not (145<=len(norm)<=830):continue
        if any(term in section for term in EXCLUDE):continue
        short=section.removeprefix("משנה תורה, הלכות ")
        if (short,chapter,law) in already:continue
        groups[short].append((lawid,short,chapter,law,norm))
    assert len(groups)>=65, "Insufficient authentic Rambam topics"
    randomizer=random.Random(20261112)
    for group in groups.values():
        randomizer.shuffle(group)
    synthetic=[]
    used=set(already)
    used_chapters=collections.defaultdict(set)
    section_names=sorted(groups)
    # Round robin over sections and chapters, no single book dominates.
    for round_number in range(12):
        for section in section_names:
            if len(synthetic)>=360:break
            items=groups[section]
            picked=None
            for index,row in enumerate(items):
                _,sec,chapter,ordinal,norm=row
                if (sec,chapter,ordinal) in used:continue
                if chapter in used_chapters[sec] and round_number<7:continue
                evidence=source_excerpt(norm,(round_number+index)%17)
                if not evidence:continue
                picked=(row,evidence)
                del items[index]
                break
            if picked is None:continue
            row,(cue,anchor)=picked
            lawid,sec,chapter,ordinal,norm=row
            question=TEMPLATES[len(synthetic)%len(TEMPLATES)].format(cue=cue)
            if not normalize(anchor) in normalize(norm):continue
            synthetic.append({
                "tier":4 if len(synthetic)%3==0 else 3,
                "question":question,
                "section":sec,
                "chapter":chapter,
                "law":ordinal,
                "highlight_contains":anchor,
                "origin":"synthetic-source-derived",
                "difficulty":2,
                "human_authored":False,
                "independent_free_paraphrase":False,
            })
            used.add((sec,chapter,ordinal))
            used_chapters[sec].add(chapter)
        if len(synthetic)>=360:break
    assert len(synthetic)==360, f"Only {len(synthetic)} unique source cases"
    cases=manual+synthetic
    assert len(cases)==500
    assert len({x["question"] for x in cases})==500
    assert sum(x["law"]>1 for x in cases)>=380
    assert len({x["section"] for x in synthetic})>=65
    for number,case in enumerate(cases,1):
        row=db.execute(
            "SELECT body FROM laws WHERE section=? AND chapter=? AND halakhah=?",
            ("משנה תורה, הלכות "+case["section"],
                case["chapter"],case["law"])
        ).fetchone()
        assert row,("Missing reference",number)
        assert normalize(case["highlight_contains"]) in normalize(row[0]),(
            "Gold anchor not literally in source",number,case["section"])
    OUTPUT.parent.mkdir(parents=True,exist_ok=True)
    OUTPUT.write_text(json.dumps(cases,ensure_ascii=False,indent=2)+"\n")
    DEBUG.parent.mkdir(parents=True,exist_ok=True)
    DEBUG.write_text(json.dumps(cases,ensure_ascii=False,indent=2)+"\n")
    result={
        "total":len(cases),
        "previous_freeform":100,
        "reviewed_nonopening":40,
        "synthetic_source_derived":360,
        "different_synthetic_sections":len({x["section"] for x in synthetic}),
        "nonopening_count":sum(x["law"]>1 for x in cases),
        "source_label_integrity":500,
        "source_anchor_integrity":500,
        "actual_android_top1_accuracy":None,
        "actual_android_highlight_accuracy":None,
        "limitation":"360 are generated from the source and are NOT independent human questions; 500/500 source integrity does not mean 500/500 retrieval"
    }
    REPORT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print("QA500_GOLD_CREATED",json.dumps(result,ensure_ascii=False),flush=True)

if __name__=="__main__":make()
