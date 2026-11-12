#!/usr/bin/env python3
"""Independent 40 NON-FIRST-law candidate test; no APK build.
Evaluates source-only character recall and legal-vocabulary bridges.
Never uses gold references to choose candidates.
"""
from __future__ import annotations
import json,sqlite3
from pathlib import Path
from test_char_trigram_bridges_100 import CharIndex,bridges
ROOT=Path(__file__).resolve().parents[1]

def main():
    db=sqlite3.connect(ROOT/"app/src/main/assets/rambam.sqlite")
    ids={(s.removeprefix("משנה תורה, הלכות "),ch,law):id
         for id,s,ch,law in db.execute(
             "SELECT id,section,chapter,halakhah FROM laws")}
    data=json.loads((ROOT/"scripts/quality_holdout_nonopening_40.json").read_text())
    assert len(data)==40 and all(x["law"]>1 for x in data)
    index=CharIndex()
    for top in (20,40,80,140,240,500):
        pure=combined=0;missing=[]
        for i,item in enumerate(data,1):
            law=ids[(item["section"],item["chapter"],item["law"])]
            raw=index.nearest(item["question"],top)
            union=set(raw)
            for bridge in bridges(item["question"]):
                union.update(index.nearest(bridge,top))
            pure+=law in raw
            combined+=law in union
            if law not in union:missing.append(i)
        print(f"HOLDOUT40_CHAR_RECALL top={top} char={pure}/40 "
              f"bridged={combined}/40 missing={missing}",flush=True)
        if top==240:
            assert combined==40, "Even broad language-to-terminology bridges missed held-out law source"
    print("HOLDOUT40_CHAR_SOURCE_ONLY complete; no end-to-end Top1 test")

if __name__=="__main__":main()
