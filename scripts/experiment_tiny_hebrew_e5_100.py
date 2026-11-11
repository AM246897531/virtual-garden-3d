#!/usr/bin/env python3
"""Independent, no-APK Hebrew semantic retrieval experiment.
Gold labels are loaded solely for scoring, NEVER for choosing candidates.
Do not confuse retrieval recall with passage-highlight correctness.
"""
from __future__ import annotations
import json, os, sqlite3, time, re, unicodedata
from pathlib import Path
import numpy as np
from sentence_transformers import SentenceTransformer
from sklearn.feature_extraction.text import TfidfVectorizer

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/"app/src/main/assets/rambam.sqlite"
GOLD=ROOT/"scripts/quality_natural_100.json"
OUT=ROOT/"artifacts/experimental_e5_100.json"
MODEL=os.environ.get("HEBREW_EMBED_MODEL","alphaedge-ai/multilingual-e5-small-heb-32768")
os.environ.setdefault("TOKENIZERS_PARALLELISM","false")

def normalized(s):
    s=unicodedata.normalize("NFKC",s)
    s=re.sub(r"[\u0591-\u05c7]","",s)
    return re.sub(r"\s+"," ",re.sub(r"[^א-תa-zA-Z0-9\s]"," ",s)).strip()

def ref(section,ch,law):
    return (section.removeprefix("משנה תורה, הלכות "),int(ch),int(law))

def run():
    import torch
    torch.set_num_threads(max(1,min(4,os.cpu_count() or 2)))
    assert DB.exists(),"Source DB missing: run build_corpus.py first"
    gold=json.loads(GOLD.read_text())
    assert len(gold)==100
    db=sqlite3.connect(DB)
    rows=db.execute("SELECT id, section, chapter, halakhah, body FROM laws ORDER BY id").fetchall()
    assert len(rows)>=15000
    labels=[ref(r[1],r[2],r[3]) for r in rows]
    questions=[g["question"] for g in gold]
    expected=[(g["section"],int(g["chapter"]),int(g["law"])) for g in gold]
    t0=time.monotonic()
    model=SentenceTransformer(MODEL,device="cpu")
    model.max_seq_length=220
    # Model expects E5 query / passage prefixes; keep actual source unaltered.
    inputs=["passage: "+r[1].replace("משנה תורה, ","")+" פרק "+str(r[2])+" הלכה "+str(r[3])+". "+normalized(r[4])[:640] for r in rows]
    queries=["query: "+q for q in questions]
    documents=model.encode(inputs,batch_size=24,show_progress_bar=True,
                           normalize_embeddings=True,convert_to_numpy=True).astype("float32")
    qvec=model.encode(queries,batch_size=24,normalize_embeddings=True,
                      convert_to_numpy=True).astype("float32")
    sim=qvec@documents.T
    del documents,qvec,model
    # Reference-independent char TF-IDF is a distinct corroborating signal.
    vectorizer=TfidfVectorizer(analyzer="char",ngram_range=(3,5),
                              min_df=2,max_features=200000,sublinear_tf=True)
    x=vectorizer.fit_transform([normalized(r[4])[:950] for r in rows])
    q=vectorizer.transform([normalized(s) for s in questions])
    lex=(q@x.T).toarray().astype("float32")
    # No tuning on gold outcomes: predeclare three fixed weights.
    mixes={"semantic_only":(1.0,0.0),"semantic_lexical":(.75,.25),
           "balanced":(.50,.50)}
    reports={}
    cases=[]
    for name,(a,b) in mixes.items():
        scores=a*sim+b*lex
        ranks=[]
        for i,want in enumerate(expected):
            candidates=np.argsort(-scores[i])[:20].tolist()
            possible=np.where(np.array([lab==want for lab in labels]))[0]
            goldpos=int(possible[0]) if len(possible) else -1
            rank=int(np.sum(scores[i]>scores[i,goldpos])+1) if goldpos>=0 else len(rows)+1
            ranks.append(rank)
            if name=="semantic_lexical":
                cases.append({"case":i+1,"question":questions[i],
                    "expected":" ".join(map(str,want)),
                    "top20":[" ".join(map(str,labels[j])) for j in candidates],
                    "top1_correct":labels[candidates[0]]==want,
                    "gold_rank":rank})
        reports[name]={"top1":sum(v==1 for v in ranks),
                       "recall_at_5":sum(v<=5 for v in ranks),
                       "recall_at_20":sum(v<=20 for v in ranks),
                       "median_rank":float(np.median(ranks))}
    data={"test_type":"NO_APK experimental dual-encoder retrieval",
          "model":MODEL,"parameters_approx":34222464,
          "questions":100,"documents":len(rows),
          "runtime_seconds":round(time.monotonic()-t0,1),
          "results":reports,"cases":cases,
          "android_highlighting_accuracy":None,
          "limitations":["No Android APK compiled","No answer span evaluation",
             "One question may have several legitimate halakhot",
             "Selected gold source alone is not proof of exclusive relevance"]}
    OUT.parent.mkdir(parents=True,exist_ok=True)
    OUT.write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n")
    print("E5_100_EXPERIMENT "+json.dumps(reports),flush=True)
if __name__=="__main__":run()
