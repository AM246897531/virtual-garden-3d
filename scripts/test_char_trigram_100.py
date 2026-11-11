#!/usr/bin/env python3
"""Validate cross-language index format and independently labeled Top-240 recall.
No APK build and no neural model run in this test.
"""
from __future__ import annotations
import collections
import json
import math
import sqlite3
import struct
from pathlib import Path

import numpy as np
from build_corpus import normalize

ROOT=Path(__file__).resolve().parents[1]
ASSET=ROOT/"app/src/main/assets/rambam_char3.bin"
GOLD=ROOT/"scripts/quality_natural_100.json"
DB=ROOT/"app/src/main/assets/rambam.sqlite"

def main():
    data=ASSET.read_bytes()
    assert data[:8]==b"RCHAR301", "Unexpected source index format"
    docs,terms,postings=struct.unpack_from("<III",data,8)
    assert 15000<=docs<=30000 and 1000<=terms<=100000
    pos=20
    law_ids=np.frombuffer(data,dtype="<i4",count=docs,offset=pos)
    pos+=4*docs
    lookup={}
    last=0
    for i in range(terms):
        size=data[pos];pos+=1
        gram=data[pos:pos+size].decode("utf-8");pos+=size
        offset,count,idf=struct.unpack_from("<IIf",data,pos);pos+=12
        assert offset==last and idf>0 and math.isfinite(idf)
        assert gram not in lookup
        lookup[gram]=(offset,count,idf)
        last+=count
    assert last==postings and len(data)==pos+postings*6
    entries=np.ndarray((postings,),dtype=[("doc","<u4"),("weight","<u2")],
                       buffer=data,offset=pos)
    assert entries["doc"].max()<docs
    db=sqlite3.connect(DB)
    refs={
        (section.removeprefix("משנה תורה, הלכות "),ch,law):doc_id
        for doc_id,section,ch,law in db.execute(
            "SELECT id,section,chapter,halakhah FROM laws")}
    assert len(refs)==docs
    cases=json.loads(GOLD.read_text(encoding="utf-8"))
    assert len(cases)==100
    hits={}
    for case in cases:
        q=normalize(case["question"])
        grams=collections.Counter(q[i:i+3] for i in range(len(q)-2))
        weights={k:(1+math.log(v))*lookup[k][2]
                 for k,v in grams.items() if k in lookup}
        norm=math.sqrt(sum(v*v for v in weights.values()))
        scores=np.zeros(docs,dtype=np.float32)
        if norm>0:
            for term,w in weights.items():
                offset,count,_=lookup[term]
                p=entries[offset:offset+count]
                np.add.at(scores,p["doc"],
                          (w/norm)*p["weight"].astype(np.float32)/65535)
        expected=refs[(case["section"],case["chapter"],case["law"])]
        doc_index=int(np.where(law_ids==expected)[0][0])
        rank=int(np.sum(scores>scores[doc_index])+1)
        hits[rank]=hits.get(rank,0)+1
    def recall(k):return sum(n for rank,n in hits.items() if rank<=k)
    report={"documents":docs,"bytes":len(data),"ngrams":terms,
        "recall@1":recall(1),"recall@20":recall(20),
        "recall@240":recall(240),"recall@1000":recall(1000),
        "type":"independent source candidate recall, NOT Android Top-1 or highlighting"}
    print("CHAR_TRIGRAM_100_RECALL",json.dumps(report),flush=True)
    assert recall(240)>=90, "Character retrieval fell below verified baseline"

if __name__=="__main__":main()
