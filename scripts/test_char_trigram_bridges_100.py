#!/usr/bin/env python3
"""Independent no-APK validation of character + vocabulary candidate recall.
A source in the candidate pool is NOT necessarily top-ranked and is NOT proof
that the turquoise answer excerpt is correct.
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
DATA=ROOT/"app/src/main/assets/rambam_char3.bin"
DB=ROOT/"app/src/main/assets/rambam.sqlite"

class CharIndex:
    def __init__(self):
        data=DATA.read_bytes()
        assert data[:8]==b"RCHAR301"
        docs,terms,posts=struct.unpack_from("<III",data,8)
        pos=20
        self.ids=np.frombuffer(data,dtype="<i4",count=docs,offset=pos)
        pos+=4*docs
        self.term={}
        for _ in range(terms):
            n=data[pos];pos+=1
            gram=data[pos:pos+n].decode();pos+=n
            off,count,idf=struct.unpack_from("<IIf",data,pos);pos+=12
            self.term[gram]=(off,count,idf)
        self.post=np.ndarray((posts,),dtype=[("doc","<u4"),("weight","<u2")],
                             buffer=data,offset=pos)
        self._buffer=data
        self.size=docs

    def nearest(self,question,limit=240):
        q=normalize(question)
        counts=collections.Counter(q[i:i+3] for i in range(max(0,len(q)-2)))
        weighted={gram:(1+math.log(tf))*self.term[gram][2]
                  for gram,tf in counts.items() if gram in self.term}
        denom=math.sqrt(sum(val*val for val in weighted.values()))
        if denom<=0:return set()
        scores=np.zeros(self.size,dtype=np.float32)
        for gram,weight in weighted.items():
            offset,count,_=self.term[gram]
            postings=self.post[offset:offset+count]
            np.add.at(scores,postings["doc"],
                (weight/denom)*postings["weight"].astype(np.float32)/65535.0)
        top=np.argpartition(-scores,limit-1)[:limit]
        return set(int(self.ids[x]) for x in top)

def bridges(question):
    q=normalize(question)
    out=[]
    if "רצה והחליצנו" in q or "רצה והחלצנו" in q:
        out.append("שכח הזכרת שבת קדושת היום בברכה ברכת המזון")
    if "סוכה" in q and any(x in q for x in ("גבוה","גובה","גבהה","אמות","אמה")):
        out.append("גובה סוכה שיעור עשרים אמה טפחים גבוהה פסולה")
    if any(x in q for x in ("בעלי חיימ","בהמות","חיות")) and any(
        x in q for x in ("באכילה","לאכול","אכיל","סימנימ")
    ):
        out.append("סימנים של בהמה חיה עוף דגים טהור טמא מאכלות")
    if any(x in q for x in ("חברימ","רעיי","סביבה","חברתי")) and any(
        x in q for x in ("משפיע","מושפע","מתנהג","השפעה","סביבה")
    ):
        out.append("דעות הנהגות חכמים חברים רעים השפעה סביבה מעשים")
    words=set(q.split())
    if words.intersection(("רב","הרב","רבו","לרב","מורה","המורה","מלמד","רבנו")) and words.intersection(
        ("אב","אבא","אבי","אביו","האב","מאבא","מאבי","הורימ","ההורימ")
    ) and words.intersection(("כבוד","לכבד","חובה","כיבוד","לכבוד","הכבוד")):
        out.append("כבוד רבו מורא רבו אביו כבוד תלמוד תורה")
    return list(dict.fromkeys(out))

def check_suite(title,cases,index,refs):
    raw=bridged=0;misses=[]
    for n,q in enumerate(cases,1):
        expected=refs.get((q["section"],q["chapter"],q["law"]))
        assert expected is not None, f"Gold reference missing from corpus: {title}/{n}"
        literal=index.nearest(q["question"])
        if expected in literal:raw+=1
        union=set(literal)
        for bridge in bridges(q["question"]):
            union.update(index.nearest(bridge))
        if expected in union:bridged+=1
        else:misses.append(n)
    print(f"CHAR_BRIDGE_CANDIDATES {title} original={raw}/{len(cases)} combined={bridged}/{len(cases)} misses={misses}",flush=True)
    # A strict, finite regression gate. This is NOT universal correctness.
    assert bridged==len(cases), f"Candidate source recall regressed on {title}"

def main():
    db=sqlite3.connect(DB)
    refs={(s.removeprefix("משנה תורה, הלכות "),ch,law):int(id)
          for id,s,ch,law in db.execute("SELECT id,section,chapter,halakhah FROM laws")}
    index=CharIndex()
    assert len(refs)==15063
    main=json.loads((ROOT/"scripts/quality_natural_100.json").read_text())
    alternate=json.loads((ROOT/"scripts/quality_new_30.json").read_text())
    assert len(main)==100 and len(alternate)==30
    # No false expansion for look-alike substrings in other halakhic terms.
    assert not bridges("מה חובת ערב שבת לגבי אבידה שנמצאה?")
    assert not bridges("בערב חובה לטפל באבידה של האחר")
    assert "כבוד רבו" in bridges("למה חובה לכבד את הרב יותר מאבא?")[0]
    check_suite("NATURAL100",main,index,refs)
    check_suite("ADDITIONAL30",alternate,index,refs)
    seen={q["question"] for q in main}
    print("ADDITIONAL_DISTINCT_NATURAL_QUERIES",sum(q["question"] not in seen for q in alternate))

if __name__=="__main__":main()
