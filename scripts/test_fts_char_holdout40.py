#!/usr/bin/env python3
"""Reproduce the *candidate* union of text FTS and char3 on fresh 40 queries.

An independent Python proxy, not a reimplementation of Android ranking:
gold law IDs are never passed into the retrieval methods, only to metrics.
The source-only SQLite passage index is built separately first. No APK build.
"""
from __future__ import annotations
import collections
import json
import re
import sqlite3
from pathlib import Path
from build_corpus import normalize
from test_char_trigram_bridges_100 import CharIndex,bridges

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/"app/src/main/assets/rambam.sqlite"
SUITE=ROOT/"scripts/quality_holdout_nonopening_40.json"
STOP={"אני","מה","איזה","איזו","האם","הוא","היא","של","עם","על","זה",
      "אלו","צריך","את","איך","מי","מתי","איפה","כיצד","מדוע","למה",
      "רוצה","מישהו","אדמ","הרמבמ","רמבמ","לפי","במקרה","כדאי",
      "מהי","מצוה","מצות","בתורה","הלכה","מקור","כתוב"}
PREFIXES=set("בכהלמוש")

def choose_terms(q):
    words=normalize(q).split()
    terms=list(dict.fromkeys(t for t in words if len(t)>=3 and t not in STOP))
    return terms[:16]

def evidence_query(term):
    values=[term]
    if len(term)>4 and term[0] in PREFIXES:
        values.append(term[1:])
    values=[v for v in dict.fromkeys(values) if len(v)>=3]
    expression=" OR ".join(v+"*" for v in values)
    return "("+expression+")" if len(values)>1 else expression

def passage_fts_ids(db,expression,limit):
    # Like the Android FTS strategy, return a bounded number of matches
    # from every longitudinal index slice.
    total=db.execute("SELECT COUNT(*) FROM passages").fetchone()[0]
    bins=8
    window=max(1,(limit+bins-1)//bins)
    ids=set()
    try:
        for segment in range(bins):
            lo=segment*total//bins+1
            hi=(segment+1)*total//bins
            rows=db.execute(
                "SELECT rowid FROM passage_fts WHERE passage_fts MATCH ? "
                "AND rowid>=? AND rowid<=? LIMIT ?",
                (expression,lo,hi,window)).fetchall()
            ids.update(row[0] for row in rows)
    except sqlite3.OperationalError:
        pass
    return ids

def fts_laws(db,question):
    groups=[evidence_query(t) for t in choose_terms(question)][:8]
    target=set()
    for i in range(min(4,len(groups))):
        for j in range(i+1,min(i+3,len(groups))):
            target.update(passage_fts_ids(db,groups[i]+" AND "+groups[j],1000))
    for group in groups:
        target.update(passage_fts_ids(db,group,800))
    if not target:return set()
    matched=set()
    selected=list(target)
    for batch_start in range(0,len(selected),300):
        batch=selected[batch_start:batch_start+300]
        placeholders=",".join("?"*len(batch))
        matched.update(r[0] for r in db.execute(
            f"SELECT DISTINCT law_id FROM passages WHERE id IN ({placeholders})",batch))
    return matched

def main():
    db=sqlite3.connect(DB)
    assert db.execute("SELECT COUNT(*) FROM passages").fetchone()[0]>=15000
    laws={(sec.removeprefix("משנה תורה, הלכות "),ch,hal):id
          for id,sec,ch,hal in db.execute(
              "SELECT id,section,chapter,halakhah FROM laws")}
    gold=json.loads(SUITE.read_text(encoding="utf-8"))
    assert len(gold)==40 and all(item["law"]>1 for item in gold)
    char=CharIndex()
    # FTS is expensive; query each question ONCE and reuse its immutable hits
    # when sweeping different char-index candidate depths.
    fts_by_question=[fts_laws(db,item["question"]) for item in gold]
    for count in (80,140,240):
        direct=combined=0
        missing=[]
        fts_sizes=[]
        for idx,item in enumerate(gold,1):
            expected=laws[(item["section"],item["chapter"],item["law"])]
            hits=char.nearest(item["question"],count)
            for bridge in bridges(item["question"]):
                hits.update(char.nearest(bridge,count))
            direct+=expected in hits
            fallback=fts_by_question[idx-1]
            fts_sizes.append(len(fallback))
            hits.update(fallback)
            if expected in hits:combined+=1
            else:missing.append(idx)
        print(f"HEBREW_FTS_CHAR40 char_top{count}={direct}/40 "
              f"union={combined}/40 avg_extra_fts_laws={sum(fts_sizes)//40} "
              f"missing={missing}",flush=True)
    print("HEBREW_FTS_CHAR40 source-candidate-only; Android Top1 and spans UNTESTED")

if __name__=="__main__":main()
