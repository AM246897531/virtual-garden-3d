#!/usr/bin/env python3
"""Investigate improved fully offline cross-encoder reranking. No release claims.

The frozen 110 labels are read ONLY after results have been ranked. Model sees
only query and source passage, never expected book/chapter/halakhah or case ID.
License: original BAAI/bge-reranker-v2-m3 Apache-2.0.
"""
import json, os, sqlite3, time, unicodedata
from pathlib import Path
import numpy as np
from scipy.sparse import csr_matrix
from sklearn.feature_extraction.text import TfidfVectorizer
from huggingface_hub import hf_hub_download
from transformers import AutoTokenizer
import onnxruntime as ort

ROOT=Path(__file__).resolve().parents[1]
MODEL='onnx-community/bge-reranker-v2-m3-ONNX'
OUT=ROOT/'artifacts'

def clean(x):
    return ''.join(ch for ch in unicodedata.normalize('NFKD',x) if unicodedata.category(ch)!='Mn')

def main():
    start=time.monotonic()
    db=sqlite3.connect(ROOT/'app/src/main/assets/rambam.sqlite')
    cases=json.loads((ROOT/'scripts/quality_full_110.json').read_text())
    rows=db.execute('SELECT p.law_id,l.section,l.chapter,l.halakhah,l.body,p.start,p.end,p.norm FROM passages p JOIN laws l ON l.id=p.law_id ORDER BY p.id').fetchall()
    texts=[r[7]+' '+r[1].split('הלכות ')[-1] for r in rows]
    print('PASSAGES',len(rows),'QUESTIONS',len(cases),flush=True)
    # Entirely label-free first stage, with two separate concept/spelling views.
    vec=TfidfVectorizer(analyzer='char',ngram_range=(2,4),max_features=130000,min_df=2,dtype=np.float32,sublinear_tf=True)
    X=vec.fit_transform(texts)
    Q=vec.transform([clean(r['question']) for r in cases])
    candidates=[]
    for i,case in enumerate(cases):
        score=(Q[i]@X.T).tocsr()
        top=np.argsort(-score.data)[:160]
        seen=set();choice=[]
        for j in top:
            row_ix=int(score.indices[j]);lid=rows[row_ix][0]
            if lid in seen:continue
            seen.add(lid);choice.append(row_ix)
            if len(choice)>=36:break
        candidates.append(choice)
    del X,Q,vec
    print('CANDIDATES_BUILT',round(time.monotonic()-start,1),flush=True)
    print('MODEL_DOWNLOAD_BEGIN',MODEL,flush=True)
    model_file=hf_hub_download(repo_id=MODEL,filename='onnx/model_int8.onnx')
    tokenizer=AutoTokenizer.from_pretrained(MODEL)
    opts=ort.SessionOptions();opts.intra_op_num_threads=3;opts.inter_op_num_threads=1
    sess=ort.InferenceSession(model_file,sess_options=opts,providers=['CPUExecutionProvider'])
    names={x.name for x in sess.get_inputs()}
    print('MODEL_LOADED',[(x.name,x.shape) for x in sess.get_inputs()],flush=True)
    OUT.mkdir(exist_ok=True)
    report=[];right=0;top5=0;candidate_recall=0
    for i,(case,chosen) in enumerate(zip(cases,candidates)):
        expected=('משנה תורה, הלכות '+case['section'],case['chapter'],case['law'])
        candidate_recall+=any((rows[j][1],rows[j][2],rows[j][3])==expected for j in chosen)
        scores=[]
        for start_ix in range(0,len(chosen),6):
            batch=chosen[start_ix:start_ix+6]
            src=[clean(rows[j][4][rows[j][5]:rows[j][6]]) for j in batch]
            snippets=[('הלכות '+rows[j][1].split('הלכות ')[-1]+': '+t) for j,t in zip(batch,src)]
            inp=tokenizer([case['question']]*len(batch),snippets,padding=True,truncation=True,max_length=192,return_tensors='np')
            feed={k:np.asarray(v,dtype=np.int64) for k,v in inp.items() if k in names}
            if 'token_type_ids' in names and 'token_type_ids' not in feed:feed['token_type_ids']=np.zeros_like(feed['input_ids'])
            logits=sess.run(None,feed)[0]
            scores.extend(float(z) for z in (logits[:,1]-logits[:,0] if logits.ndim==2 and logits.shape[1]==2 else logits.reshape(-1)))
        rank=np.argsort(-np.asarray(scores))
        top=[(rows[chosen[int(j)]][1],rows[chosen[int(j)]][2],rows[chosen[int(j)]][3]) for j in rank[:10]]
        first=next((n+1 for n,r in enumerate(top) if r==expected),None)
        right+=first==1;top5+=first is not None and first<=5
        report.append(dict(question=case['question'],expected=expected,top=top[:5],first_rank=first))
        print('BGE_CASE',i+1,'PASS' if first==1 else 'FAIL','rank=',first,'expected=',expected,'top=',top[0] if top else None,flush=True)
    outcome=dict(model=MODEL,license='Apache-2.0',test_questions=len(cases),top1=right,top5=top5,candidate_recall=candidate_recall,elapsed=round(time.monotonic()-start,1),results=report)
    (OUT/'bge-110.json').write_text(json.dumps(outcome,ensure_ascii=False,indent=2))
    print('BGE_COMPLETE',right,'/',len(cases),'top5=',top5,'candidate_recall=',candidate_recall,'seconds=',outcome['elapsed'],flush=True)

if __name__=='__main__':main()
