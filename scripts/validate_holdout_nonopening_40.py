#!/usr/bin/env python3
"""Verify 40 independently authored non-opening law question/answer anchors.
The question source annotations exist only in test assets, never in retrieval.
This verifies the source-label integrity, NOT retrieval/answer accuracy.
"""
import json
import sqlite3
from pathlib import Path
from build_corpus import normalize

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/"app/src/main/assets/rambam.sqlite"
SUITE=ROOT/"scripts/quality_holdout_nonopening_40.json"
OUT=ROOT/"artifacts/holdout_nonopening_40_report.json"

def main():
    tests=json.loads(SUITE.read_text(encoding="utf-8"))
    assert len(tests)==40
    assert len({x["question"] for x in tests})==40
    assert all(x["law"]>1 for x in tests)
    db=sqlite3.connect(DB)
    report=[]
    for number,case in enumerate(tests,1):
        target=db.execute(
            "SELECT body FROM laws WHERE section=? AND chapter=? AND halakhah=?",
            ("משנה תורה, הלכות "+case["section"],
                case["chapter"],case["law"])
        ).fetchone()
        source_found=target is not None
        required=normalize(case["highlight_contains"])
        text=normalize(target[0]) if target else ""
        present=bool(required) and required in text
        report.append({"case":number,"source":f'{case["section"]} {case["chapter"]}:{case["law"]}',
            "source_exists":source_found,"answer_anchor_present":present,
            "question":case["question"]})
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text(json.dumps({"test_kind":"manual-natural-question gold source integrity only",
        "total":len(tests),"references":sum(x["source_exists"] for x in report),
        "answer_anchors":sum(x["answer_anchor_present"] for x in report),
        "android_accuracy":None,"cases":report},ensure_ascii=False,indent=2)+"\n")
    bad=[x for x in report if not x["source_exists"] or not x["answer_anchor_present"]]
    print(f"HOLDOUT_NATURAL_NONOPENING references={len(tests)-sum(not x['source_exists'] for x in report)}/{len(tests)} "
          f"anchors={len(tests)-sum(not x['answer_anchor_present'] for x in report)}/{len(tests)}")
    for item in bad:print("BAD_GOLD_SOURCE_OR_ANSWER",item)
    assert not bad,"Correct the gold labels; never train/tune on invalid expectations"

if __name__=="__main__":main()
