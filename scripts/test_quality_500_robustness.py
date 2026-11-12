#!/usr/bin/env python3
"""500-question source recall under verbose, irrelevant conversational wrapping.

A labelled law is used ONLY in evaluation, never in candidate selection.
Passing this does NOT prove first result or turquoise answer accuracy.
"""
from __future__ import annotations
import collections,json,sqlite3
from pathlib import Path
from test_char_trigram_bridges_100 import CharIndex,bridges
from test_verbose_query_recall_100 import sentence_segments

ROOT=Path(__file__).resolve().parents[1]
MAIN=ROOT/"artifacts/quality_500_gold.json"
DB=ROOT/"app/src/main/assets/rambam.sqlite"
OUT=ROOT/"artifacts/quality_500_robustness.json"
INTRO="אני מבקש להבין את ההלכה המקורית ולא להסתמך על סיכום. "
SUFFIX=" תודה, אני מחפש את המקור המדויק בלי להמציא מה ההלכה אומרת."

def variants(question):
    return {
      "original":question,
      "verbose_prefix":INTRO+question,
      "verbose_prefix_and_suffix":INTRO+question+SUFFIX,
    }

def source_candidates(index,question):
    result=index.nearest(question,240)
    for expansion in bridges(question):
        result.update(index.nearest(expansion,240))
    for sentence in sentence_segments(question):
        result.update(index.nearest(sentence,240))
    return result

def main():
    db=sqlite3.connect(DB)
    ref={(s.removeprefix("משנה תורה, הלכות "),c,l):id
          for id,s,c,l in db.execute(
              "SELECT id,section,chapter,halakhah FROM laws")}
    cases=json.loads(MAIN.read_text(encoding="utf-8"))
    assert len(cases)==500
    index=CharIndex()
    reports={}
    for key in ("original","verbose_prefix","verbose_prefix_and_suffix"):
        total=0;correct=0;bytype=collections.Counter();missing=[]
        for i,case in enumerate(cases,1):
            query=variants(case["question"])[key]
            gold=ref[(case["section"],case["chapter"],case["law"])]
            candidates=source_candidates(index,query)
            present=gold in candidates
            correct+=present
            origin="synthetic" if case.get("origin")=="synthetic-source-derived" else "freeform"
            bytype[f"{origin}_total"]+=1
            bytype[f"{origin}_present"]+=present
            total+=len(candidates)
            if not present:missing.append(i)
        report={"found":correct,"total":500,"average_candidates":round(total/500,1),
            "cohorts":dict(bytype),"missing_cases":missing}
        reports[key]=report
        print(f"QA500_VERBOSE_RECALL {key} "
              f"source_candidate_recall={correct}/500 "
              f"average_candidates={report['average_candidates']} "
              f"missing={missing}",flush=True)
    OUT.write_text(json.dumps({
        "kind":"Python candidate union robustness only, not Android Top-1",
        "variants":reports,
        "android_accuracy":None,
        "highlight_accuracy":None},ensure_ascii=False,indent=2)+"\n")
    assert reports["original"]["found"]==500
    # Never downgrade a known-good exact lexical baseline for verbose prompts.
    # Failures in these variants are tracked explicitly rather than labelled
    # 100% with a changed denominator.
    assert all(item["found"]>=495 for item in reports.values()), (
        "Long conversational wrappers still lose too many Rambam sources"
    )

if __name__=="__main__":main()
