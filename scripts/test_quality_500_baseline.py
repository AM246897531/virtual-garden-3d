#!/usr/bin/env python3
"""500 question LOCAL source retrieval + turquoise evidence BASELINE audit.

This tests a SELF-CONTAINED Python proxy for character cosine Top-1 and a
question-to-original-text sliding window. It does NOT test the Kotlin engine.
Gold labels are read ONLY after ranking / span prediction. Every miss is
recorded. The 360 generated questions are source-derived and not human-written.
The strict Android Q500 test is a separate acceptance gate.
"""
from __future__ import annotations
import collections
import json
import math
import re
import sqlite3
from pathlib import Path
import numpy as np
from build_corpus import normalize
from build_gold_answer_offsets import normalized_with_offsets
from test_char_trigram_bridges_100 import CharIndex,bridges

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/"app/src/main/assets/rambam.sqlite"
SUITE=ROOT/"artifacts/quality_500_gold.json"
REPORT=ROOT/"artifacts/quality_500_source_baseline.json"
NORMALIZED_WORD=re.compile(r"[\u05d0-\u05ea\u0591-\u05c7]+")
SKIP={"הרמבמ","הרמבם","מקור","מקרה","מדויק","משנה","תורה",
      "איפה","למה","מה","האם","במצב","ההלכה","דינ","מקרה",
      "אומר","צריך","לקבוע","מתיחס","ברמבמ","לפי","הבא"}

def ranked_char(index,question,limit=500):
    # Use the exact RCHAR301 term frequencies, quantized document TF-IDF
    # and source-only inverse-frequency lookup, independent of the gold label.
    q=normalize(question)
    counts=collections.Counter(q[i:i+3] for i in range(max(0,len(q)-2)))
    weights={k:(1+math.log(v))*index.term[k][2]
             for k,v in counts.items() if k in index.term}
    denominator=math.sqrt(sum(v*v for v in weights.values()))
    if not denominator:return []
    scores=np.zeros(index.size,dtype=np.float32)
    for gram,weight in weights.items():
        off,count,_=index.term[gram]
        posts=index.post[off:off+count]
        np.add.at(scores,posts["doc"],
                  (weight/denominator)*posts["weight"].astype(np.float32)/65535)
    limit=min(limit,len(scores))
    best=np.argpartition(-scores,limit-1)[:limit]
    ordering=best[np.lexsort((index.ids[best],-scores[best]))]
    return [(int(index.ids[position]),float(scores[position]))
            for position in ordering if scores[position]>0]

def predict_answer_span(source:str,query:str):
    """A deliberately simple document-only baseline. NEVER sees gold anchor."""
    toks=list(NORMALIZED_WORD.finditer(source))
    if len(toks)<4:return None
    words=[normalize(t.group()) for t in toks]
    question_words={w for w in normalize(query).split() if len(w)>=3 and w not in SKIP}
    if not question_words:return None
    best=None;value=float("-inf")
    for start in range(0,len(toks),2):
        for width in (10,16,23,29):
            stop=min(start+width,len(toks))
            if stop-start<5:continue
            a=toks[start].start();b=toks[stop-1].end()
            if b-a<25 or b-a>360:continue
            chosen=words[start:stop]
            hits=len(question_words.intersection(chosen))
            cov=hits/max(1,len(question_words))
            unique=len(set(chosen))
            score=hits*7.0+cov*25.0 - (b-a)*.022 + .015*unique
            if score>value:
                value=score;best=(a,b)
    return best

def gold_bounds(source:str,anchor:str):
    normalized,offsets=normalized_with_offsets(source)
    target=normalize(anchor)
    pos=normalized.find(target)
    assert pos>=0 and target,"Gold anchor missing from original law"
    return offsets[pos],offsets[pos+len(target)-1]+1

def main():
    questions=json.loads(SUITE.read_text(encoding="utf-8"))
    assert len(questions)==500
    conn=sqlite3.connect(DB)
    rows=conn.execute("SELECT id,section,chapter,halakhah,body FROM laws ORDER BY id").fetchall()
    by_source={(s.removeprefix("משנה תורה, הלכות "),ch,hal):row[0]
               for row in rows for _,s,ch,hal,_ in [row]}
    by_id={r[0]:r for r in rows}
    model=CharIndex()
    expected_all=0;cohorts=collections.defaultdict(lambda:collections.Counter())
    failures=[]
    detail=[]
    for i,case in enumerate(questions,1):
        gold=by_source[(case["section"],case["chapter"],case["law"])]
        origin=("source-derived" if case.get("origin")=="synthetic-source-derived"
                else "preexisting-reviewed")
        ranked=ranked_char(model,case["question"],500)
        top=[r[0] for r in ranked]
        top1=bool(top and top[0]==gold)
        rank=top.index(gold)+1 if gold in top else None
        # Independent candidate routes always run without looking at gold.
        # Do NOT confuse union recall with rank #1 or highlighted answer.
        bridge_candidates=set(top[:240])
        for expanded in bridges(case["question"]):
            bridge_candidates.update(law_id for law_id,_ in ranked_char(
                model,expanded,240))
        bridge_present=gold in bridge_candidates
        stats=cohorts[origin]
        stats["candidate_union_240"]+=bridge_present
        stats["total"]+=1
        for k in (1,5,20,80,240,500):
            stats[f"hit_{k}"]+=bool(rank is not None and rank<=k)
        source=by_id[gold][4]
        a,b=gold_bounds(source,case["highlight_contains"])
        predicted=None;gold_coverage=0.;span_precision=0.;highlight_pass=False
        if top1:
            predicted=predict_answer_span(source,case["question"])
            if predicted:
                start,stop=predicted
                intersection=max(0,min(b,stop)-max(a,start))
                gold_coverage=intersection/(b-a)
                span_precision=intersection/(stop-start)
                highlight_pass=gold_coverage>=.90 and span_precision>=.20
        stats["highlight_pass"]+=highlight_pass
        if not top1 or not highlight_pass:
            failures.append({"case":i,"origin":origin,"expected":case["section"]+
                             " "+str(case["chapter"])+":"+str(case["law"]),
                "predicted_top1":(
                    by_id[top[0]][1]+" "+str(by_id[top[0]][2])+":"+
                    str(by_id[top[0]][3]) if top else None),
                "gold_candidate_rank":rank,
                "answer_highlight_overlap":round(gold_coverage,3),
                "answer_highlight_precision":round(span_precision,3)})
        detail.append({"case":i,"origin":origin,
            "top1_source_correct":top1,
            "gold_source_rank":rank,
            "candidate_union_240":bridge_present,
            "gold_span_matched":highlight_pass,
            "gold_coverage":round(gold_coverage,3),
            "gold_precision":round(span_precision,3)})
        if i%100==0:
            print(f"QA500_BASELINE_PROGRESS {i}/500",flush=True)
    results={}
    for key,value in sorted(cohorts.items()):
        results[key]=dict(value)
        print("QA500_BASELINE_COHORT "+key+" "+json.dumps(
            results[key],sort_keys=True),flush=True)
    combined=collections.Counter()
    for x in cohorts.values():combined.update(x)
    print("QA500_BASELINE_TOTAL "+json.dumps(dict(combined),sort_keys=True),flush=True)
    REPORT.write_text(json.dumps({
        "kind":"Python char3 Top1 + literal-text overlap baseline, NOT Kotlin Android results",
        "provenance":"140 existing free-form questions plus 360 source-derived cases",
        "metrics":dict(combined),
        "cohorts":results,
        "case_results":detail,
        "failures":failures,
        "android_top1_correct":None,
        "android_turquoise_highlight_correct":None,
        "limits":[
            "This is not the Android search engine",
            "A source-derived question is easier than an independently phrased question",
            "Overlap with a short gold phrase does not prove answer completeness"
        ]
    },indent=2,ensure_ascii=False)+"\n")
    assert combined["total"]==500
    assert combined["candidate_union_240"]==500, (
        "Source-only 500 gold candidate retrieval incomplete: "+
        str(combined["candidate_union_240"])+"/500"
    )
    print("QA500_BASELINE_REPORTED no_apk=True not_100_percent_claim=True")

if __name__=="__main__":main()
