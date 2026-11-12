#!/usr/bin/env python3
"""Supplementary non-opening-law contrastive retrieval stress test. NO APK BUILD.

IMPORTANT: The questions below are MECHANICALLY CONSTRUCTED near-verbatim
rephrasings of real Rambam clauses. They are NOT independent natural-language
paraphrases, nor a substitute for human-edited held-out questions.

Purpose: prevent accidental "always show the first law in a chapter" shortcuts,
check document IDs and retrieval for 100 actual laws with ordinal >= 2.
"""
from __future__ import annotations
import collections
import json
import sqlite3
from pathlib import Path
import numpy as np
from build_corpus import normalize
from test_char_trigram_bridges_100 import CharIndex

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/"app/src/main/assets/rambam.sqlite"
OUT=ROOT/"artifacts/contrastive_nonopening_100.json"
EXCLUDE_SECTION_TERMS=(
    "אישות","איסורי ביאה","גירושין","יבום וחליצה",
    "נערה בתולה","סוטה","מיאון","נדה"
)

def generate(db):
    rows=db.execute("SELECT id,section,chapter,halakhah,norm FROM laws ORDER BY id").fetchall()
    candidates=collections.defaultdict(list)
    for lawid,sec,chapter,ordinal,norm in rows:
        if ordinal<2 or not (90<=len(norm)<=700) or any(
            term in sec for term in EXCLUDE_SECTION_TERMS):
            continue
        words=norm.split()
        pos=next((i for i,w in enumerate(words)
                  if w in ("אם","ואם","כיצד","כשהוא") and i+15<len(words)),None)
        if pos is None:continue
        cue=" ".join(words[pos+1:pos+15]) if words[pos] in ("אם","ואם") else (
            " ".join(words[pos:pos+14]))
        if 50<=len(cue)<=150:
            candidates[sec].append((lawid,sec,chapter,ordinal,cue))
    selected=[]
    used_ids=set()
    for iteration in range(5):
        for section,group in sorted(candidates.items()):
            if len(selected)>=100:break
            used_chapters={v[2] for v in selected if v[1]==section}
            available=[v for v in group if v[2] not in used_chapters
                       and v[0] not in used_ids]
            if not available:continue
            value=available[(iteration*7+len(section))%len(available)]
            selected.append(value)
            used_ids.add(value[0])
        if len(selected)>=100:break
    assert len(selected)==100, f"Not enough independent non-opening laws: {len(selected)}"
    assert len({v[1] for v in selected})>=55, "Not enough halakhic topics"
    cases=[]
    for lawid,section,chapter,ordinal,cue in selected:
        cases.append({
            "question":"מה ההלכה המתייחסת למקרה הבא: "+cue+"? מצא את המקור ברמבם.",
            "section":section.removeprefix("משנה תורה, הלכות "),
            "chapter":chapter,"law":ordinal,
            "law_id":lawid,
            "origin":"synthetic-near-verbatim-heldout-source",
            "independent_human_paraphrase":False,
        })
    return cases

def main():
    db=sqlite3.connect(DB)
    cases=generate(db)
    assert len({c["law_id"] for c in cases})==100
    assert sum(c["law"]==1 for c in cases)==0
    index=CharIndex()
    present=sum(c["law_id"] in index.nearest(c["question"],240) for c in cases)
    sources=len({c["section"] for c in cases})
    print(f"NONOPENING_SOURCE_STRESS100 sources={sources} law1_count=0 "
          f"candidate_recall_at_240={present}/100 "
          "human_free_paraphrases=0/100",flush=True)
    report={"suite_kind":"synthetic source-clause discrimination; not independent human questions",
            "question_count":len(cases),"distinct_sections":sources,
            "first_law_expected_count":0,
            "top240_candidate_recall":present,
            "top1_android_accuracy":None,
            "answer_span_android_accuracy":None,
            "cases":cases}
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    assert present>=90, "Source candidate recall regressed on non-opening laws"

if __name__=="__main__":main()
