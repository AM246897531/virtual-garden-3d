#!/usr/bin/env python3
"""Experimental, uncompiled Hebrew source reranker benchmark (NO APK).

A pre-trained Apache-2.0 multilingual 141M cross-encoder is tested only on
candidate sources retrieved independently of the gold labels. Results are
NOT deployed unless verified accuracy, memory, and latency justify it.

This is expressly an EXPERIMENT: no Android Gradle, no APK, no answer text
generation, no hardcoded question->Rambam-law destinations.
"""
from __future__ import annotations
import json,math,os,re,sqlite3,time,unicodedata
from collections import defaultdict
from pathlib import Path
import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/"app/src/main/assets/rambam.sqlite"
GOLD=ROOT/"scripts/quality_natural_100.json"
SCORES=ROOT/"artifacts/experimental_e5_scores_100.npz"
OUT=ROOT/"artifacts/experimental_multilingual_reranker_100.json"
MODEL=os.environ.get("HEBREW_RERANK_MODEL","Horizon-Labs/multilingual-reranker-small")
STOP_TITLES={"תורה","הלכות"}

def normalize(s):
    s=unicodedata.normalize("NFKC",s)
    s=re.sub(r"[\u0591-\u05c7]","",s)
    s=s.translate(str.maketrans("ךםןףץ","כמנפצ"))
    return " ".join(re.sub(r"[^א-תa-zA-Z0-9]+"," ",s).split()).lower()

def root(w):
    if len(w)>=5 and w.endswith(("ות","ימ")):return w[:-2]
    if len(w)>=5 and w.endswith("ה"):return w[:-1]
    return w

def bridges(question):
    q=normalize(question)
    out=[]
    if "רצה והחליצנו" in q or "רצה והחלצנו" in q:
        out.append("שכח הזכרת שבת קדושת היום בברכה ברכת המזון")
    if "סוכה" in q and any(x in q for x in ("גבוה","גובה","גבהה","אמות","אמה")):
        out.append("גובה סוכה שיעור עשרים אמה טפחים גבוהה פסולה")
    if any(x in q for x in ("בעלי חיימ","בהמות","חיות")) and any(
            x in q for x in ("סימנימ","סימני","סימנ","כשר","טמא","טהור")):
        out.append("סימנים של בהמה חיה עוף דגים טהור טמא מאכלות")
    if any(x in q for x in ("חברימ","רעיי","סביבה","חברתי")) and any(
            x in q for x in ("משפיע","מושפע","מתנהג","השפעה","התנהגות")):
        out.append("דעות הנהגות חכמים חברים רעים השפעה סביבה מעשים")
    qwords=set(q.split())
    if qwords.intersection({"רב","הרב","רבו","לרב","מורה","המורה","מלמד","רבנו"}) and qwords.intersection(
            {"אב","אבא","אבי","אביו","האב","מאבא","מאבי","הורימ","ההורימ"}) and qwords.intersection(
            {"כבוד","לכבד","חובה","כיבוד","לכבוד","הכבוד"}):
        out.append("כבוד רבו מורא רבו אביו כבוד תלמוד תורה")
    return out

def candidates(queries,rows,sim,lex):
    docs=[r[4] for r in rows]
    char=TfidfVectorizer(analyzer="char",ngram_range=(3,3),min_df=2,
                         sublinear_tf=True,dtype=np.float32)
    X=char.fit_transform(docs)
    qchar=(char.transform([normalize(q) for q in queries])@X.T).tocsr()
    section_leads=defaultdict(list)
    for i,row in enumerate(rows):
        if row[3]==1:
            section_leads[normalize(row[1].removeprefix("משנה תורה, הלכות "))].append(i)
    pools=[]
    hybrid=.75*sim+.25*lex
    for i,q in enumerate(queries):
        # No gold source is ever referenced in this retrieval process.
        lead=set(np.argpartition(-hybrid[i],39)[:40].tolist())
        vector=qchar.getrow(i).toarray().reshape(-1)
        lead.update(np.argpartition(-vector,9)[:10].tolist())
        for bridge in bridges(q):
            score=(char.transform([normalize(bridge)])@X.T).toarray().reshape(-1)
            lead.update(np.argpartition(-score,4)[:5].tolist())
        queryroots={root(word) for word in normalize(q).split() if len(word)>=4}
        for title,locs in section_leads.items():
            parts=[x for x in title.split() if len(x)>=4 and x not in STOP_TITLES]
            if parts and len(parts)<=2 and all(root(x) in queryroots for x in parts):
                lead.update(locs)
        # Preserve approximate first-stage rank in a fixed, label-free score.
        seed=hybrid[i]
        ordered=sorted(lead,key=lambda idx:(-float(seed[idx]),idx))
        pools.append(ordered)
    return pools

def main():
    import onnxruntime as ort
    from huggingface_hub import hf_hub_download
    from transformers import AutoTokenizer
    t0=time.monotonic()
    data=np.load(SCORES)
    sem=data["semantic"].astype("float32")
    lex=data["lexical"].astype("float32")
    source_ids=data["doc_ids"]
    suite=json.loads(GOLD.read_text(encoding="utf-8"))
    assert len(suite)==100 and sem.shape[0]==100
    conn=sqlite3.connect(DB)
    rows=conn.execute(
        "SELECT id,section,chapter,halakhah,norm FROM laws ORDER BY id").fetchall()
    assert len(rows)==15063 and np.array_equal(source_ids,[r[0] for r in rows])
    index={(normalize(r[1].removeprefix("משנה תורה, הלכות ")),r[2],r[3]):i
           for i,r in enumerate(rows)}
    gold=[index[(normalize(g["section"]),g["chapter"],g["law"])] for g in suite]
    queries=[g["question"] for g in suite]
    pools=candidates(queries,rows,sem,lex)
    present=sum(gold[i] in pool for i,pool in enumerate(pools))
    print(f"RERANKER_CANDIDATES gold_in_pool={present}/100 "
          f"avg_pool={sum(len(p) for p in pools)/100:.1f}",flush=True)
    if present<95:raise RuntimeError("Candidate pool regression; refuse reranker report")

    modelpath=hf_hub_download(MODEL,filename="onnx/model_quantized.onnx")
    tokenizer=AutoTokenizer.from_pretrained(MODEL,trust_remote_code=False)
    so=ort.SessionOptions()
    so.intra_op_num_threads=4
    so.inter_op_num_threads=1
    session=ort.InferenceSession(modelpath,sess_options=so,
        providers=["CPUExecutionProvider"])
    input_names={arg.name for arg in session.get_inputs()}

    rowscores=[]
    latency=[]
    for i,q in enumerate(queries):
        sources=pools[i]
        outputs=[]
        t=time.monotonic()
        for at in range(0,len(sources),8):
            batch=sources[at:at+8]
            passages=[
                rows[j][1].removeprefix("משנה תורה, ")+" פרק "+
                str(rows[j][2])+" הלכה "+str(rows[j][3])+". "+rows[j][4][:780]
                for j in batch
            ]
            tokens=tokenizer([q]*len(batch),text_pair=passages,
                padding=True,truncation=True,max_length=384,return_tensors="np")
            feed={k:np.asarray(tokens[k],dtype=np.int64)
                  for k in input_names if k in tokens}
            assert set(feed)==input_names, ("Unexpected ONNX inputs",input_names)
            value=session.run(None,feed)[0]
            outputs.extend(np.asarray(value).reshape(-1).astype(float).tolist())
        rowscores.append(outputs)
        latency.append(time.monotonic()-t)
        if (i+1)%10==0:
            print(f"RERANKER_PROGRESS {i+1}/100 "
                  f"elapsed={time.monotonic()-t0:.1f}s",flush=True)

    metrics={}
    cases=[]
    for strategy in ("reranker_only","reranker_25pct_hybrid","reranker_50pct_hybrid"):
        acc=0;top5=0;top20=0
        for i,pool in enumerate(pools):
            raw=np.asarray(rowscores[i],dtype=np.float32)
            cross=1/(1+np.exp(-np.clip(raw,-30,30)))
            original=.75*sem[i,pool]+.25*lex[i,pool]
            a={"reranker_only":1.0,"reranker_25pct_hybrid":.75,
               "reranker_50pct_hybrid":.50}[strategy]
            fused=a*cross+(1-a)*original
            order=np.argsort(-fused,kind="stable")
            top=[pool[int(k)] for k in order]
            if gold[i] in top[:1]:acc+=1
            if gold[i] in top[:5]:top5+=1
            if gold[i] in top[:20]:top20+=1
            if strategy=="reranker_25pct_hybrid":
                cases.append({"case":i+1,"question":queries[i],
                    "expected":suite[i]["section"]+" "+
                               str(suite[i]["chapter"])+":"+str(suite[i]["law"]),
                    "candidate_present":gold[i] in pool,
                    "top1_match":bool(top and top[0]==gold[i]),
                    "gold_candidate_rank":(top.index(gold[i])+1)
                        if gold[i] in top else None,
                    "top5_refs":[rows[j][1].removeprefix("משנה תורה, הלכות ")+
                        " "+str(rows[j][2])+":"+str(rows[j][3]) for j in top[:5]]})
        metrics[strategy]={"top1":acc,"top5":top5,"top20":top20}
    result={"kind":"experimental cross-encoder ranking on 100 fixed gold references",
        "model":MODEL,"model_license":"Apache-2.0",
        "model_onnx_quantized_bytes":Path(modelpath).stat().st_size,
        "candidates_recall":present,"average_candidates":sum(map(len,pools))/100,
        "p50_rerank_seconds":float(np.median(latency)),
        "p95_rerank_seconds":float(np.percentile(latency,95)),
        "cpu_total_seconds":time.monotonic()-t0,
        "models":metrics,"cases":cases,"android_top1":None,
        "android_turquoise_answer_accuracy":None,
        "limits":["No Android compile or deployment","Reranking source law prefixes and first 780 norm characters only",
                  "Source labels are a finite set and sometimes non-exclusive",
                  "Not a complete answer-span evaluation"]}
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text(json.dumps(result,ensure_ascii=False,indent=2)+"\n")
    print("RERANKER_100_RESULT "+json.dumps(metrics),flush=True)
    print(f"RERANKER_LATENCY seconds p50={result['p50_rerank_seconds']:.2f} "
          f"p95={result['p95_rerank_seconds']:.2f}",flush=True)

if __name__=="__main__":main()
