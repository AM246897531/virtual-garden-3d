#!/usr/bin/env python3
"""100-source retrieval stress test for verbose Hebrew conversational questions.

The expected law is used ONLY AFTER retrieval to score candidate coverage.
Never use its ID, gold phrase or chapter number as an input to retrieval.
This Python source-only proxy does not certify Android Top-1 or highlighting.
"""
import json
import re
import sqlite3
from pathlib import Path
from build_corpus import normalize
from test_char_trigram_bridges_100 import CharIndex, bridges

ROOT=Path(__file__).resolve().parents[1]
INTRO="אני מנסה להבין מקרה הלכתי מעשי ושואל מפני שאינני יודע מה המקור הנכון במפורש במשנה תורה לרמבם. "
ENDING=" אני מבקש למצוא את ההלכה המדויקת בלשון הרמבם ולא הסבר משוער או מקור קשור באופן כללי."

# Deliberately simple, language-only sentence selection. Not a source map.
def sentence_segments(question):
    if len(question)<75:return []
    chunks=[s.strip() for s in re.split(r'[.!?;׃\n]+',question)]
    chunks=[s for s in chunks if 16<=len(s)<=950]
    if len(chunks)<2:return []
    graded=[]
    for order,sentence in enumerate(chunks):
        n=normalize(sentence)
        specificity=sum(len(w)>=4 for w in n.split())
        inquiry=n.startswith(("מה ","מתי ","האם ","באיזה ")) or " שכחתי " in n
        boilerplate=any(normalize(x) in n for x in (
            "אני מבקש למצוא","אני מנסה להבין",
            "המקור הנכון","ההלכה המדויקת","משנה תורה לרמב"))
        score=specificity+6*int(inquiry)-12*int(boilerplate)
        graded.append((score,order,sentence))
    graded.sort(key=lambda x:(-x[0],x[1]))
    return list(dict.fromkeys(s for score,_,s in graded[:3] if score>0))

def main():
    index=CharIndex()
    gold=json.loads((ROOT/"scripts/quality_natural_100.json").read_text())
    assert len(gold)==100
    db=sqlite3.connect(ROOT/"app/src/main/assets/rambam.sqlite")
    refs={(section.removeprefix("משנה תורה, הלכות "),ch,law):id
          for id,section,ch,law in db.execute(
              "SELECT id,section,chapter,halakhah FROM laws")}
    variants={
        "original":lambda q:q,
        "long_prefix":lambda q:INTRO+q,
        "long_suffix":lambda q:q+ENDING,
        "long_prefix_and_suffix":lambda q:INTRO+q+ENDING,
    }
    for name,wrap in variants.items():
        succeeded=0
        missing=[]
        count=0
        for i,g in enumerate(gold,1):
            question=wrap(g["question"])
            expected=refs[(g["section"],g["chapter"],g["law"])]
            pool=index.nearest(question,240)
            for segment in sentence_segments(question):
                pool.update(index.nearest(segment,240))
            for expansion in bridges(question):
                pool.update(index.nearest(expansion,240))
            count+=len(pool)
            if expected in pool:succeeded+=1
            else:missing.append(i)
        print(f"VERBOSE_RETRIEVAL_CASE {name} candidate_recall={succeeded}/100 "
              f"average_candidates={count//100} missing={missing}",flush=True)
        assert succeeded==100, f"Source candidate recall failed for {name}"
    print("VERBOSE_RETRIEVAL_100 PASS no_APK_build=True top1_UNTESTED=True")

if __name__=="__main__":main()
