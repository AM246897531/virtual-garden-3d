#!/usr/bin/env python3
"""An isolated candidate-aware Hebrew cross-encoder test.
Source-only evaluation; no known answer is inserted into candidate generation.
Apache-2.0 Horizon Labs model is NOT installed in the app unless it passes.
"""
import json,sqlite3,time,os,re,unicodedata
from pathlib import Path
import numpy as np
import onnxruntime as ort
from sklearn.feature_extraction.text import TfidfVectorizer
from transformers import AutoTokenizer
from huggingface_hub import hf_hub_download

R=Path(__file__).resolve().parents[1]
MODEL='Horizon-Labs/multilingual-reranker-small'

def unmark(s):
    return ''.join(c for c in unicodedata.normalize('NFKD',s) if unicodedata.category(c)!='Mn')

def main():
    t=time.monotonic()
    db=sqlite3.connect(R/'app/src/main/assets/rambam.sqlite')
    tests=json.loads((R/'scripts/quality_full_110.json').read_text())
    passages=db.execute('SELECT p.id,p.law_id,l.section,l.chapter,l.halakhah,l.body,p.start,p.end,p.norm FROM passages p JOIN laws l ON l.id=p.law_id ORDER BY p.id').fetchall()
    docs=[r[8]+' '+r[2].split('הלכות ')[-1] for r in passages]
    print('CANDIDATE_GENERATION',len(passages),'passages',flush=True)
    tf=TfidfVectorizer(analyzer='char',ngram_range=(2,4),max_features=130000,min_df=2,sublinear_tf=True,dtype=np.float32)
    X=tf.fit_transform(docs);Q=tf.transform([r['question'] for r in tests])
    q_candidates=[]
    for query in Q:
        scores=(query@X.T).tocsr();order=np.argsort(-scores.data)[:110]
        chosen=[];seen=set()
        for j in order:
            ix=int(scores.indices[j]);law=passages[ix][1]
            if law in seen:continue
            seen.add(law);chosen.append(ix)
            if len(chosen)>=50:break
        q_candidates.append(chosen)
    print('CHAR_CANDIDATES_READY',round(time.monotonic()-t,1),'s',flush=True)
    tok=AutoTokenizer.from_pretrained(MODEL)
    onnx=hf_hub_download(MODEL,filename='onnx/model_quantized.onnx')
    opts=ort.SessionOptions();opts.intra_op_num_threads=4;opts.inter_op_num_threads=1
    sess=ort.InferenceSession(onnx,sess_options=opts,providers=['CPUExecutionProvider'])
    names={z.name for z in sess.get_inputs()}
    print('MODEL_READY',[(z.name,z.shape) for z in sess.get_inputs()],flush=True)
    results=[];right=0;top5=0
    for i,case in enumerate(tests):
        cands=q_candidates[i];pred=[]
        for first in range(0,len(cands),8):
            part=cands[first:first+8]
            enc=tok([case['question']]*len(part),[unmark(passages[j][5][passages[j][6]:passages[j][7]]) for j in part],padding=True,truncation=True,max_length=256,return_tensors='np')
            feed={name:np.asarray(val,dtype=np.int64) for name,val in enc.items() if name in names}
            if 'token_type_ids' in names and 'token_type_ids' not in feed:feed['token_type_ids']=np.zeros_like(feed['input_ids'])
            logits=sess.run(None,feed)[0].reshape(-1)
            pred.extend(float(z) for z in logits)
        ranked=np.argsort(-np.array(pred))
        sources=[]
        for z in ranked[:10]:
            row=passages[cands[int(z)]]
            sources.append((row[2],row[3],row[4]))
        expected=('משנה תורה, הלכות '+case['section'],case['chapter'],case['law'])
        rank=next((j+1 for j,x in enumerate(sources) if x==expected),None)
        right+=rank==1;top5+=rank is not None and rank<=5
        print(f'RERANK_CASE {i+1:03d} {"PASS" if rank==1 else "FAIL"} expected={expected} top={sources[0] if sources else None} rank={rank or ">10"}',flush=True)
        results.append(dict(question=case['question'],expected=expected,top=sources[:5],rank=rank))
    result=dict(model=MODEL,license='Apache-2.0',count=len(tests),top1=right,top5=top5,elapsed=round(time.monotonic()-t,1),results=results)
    Path('artifacts').mkdir(exist_ok=True)
    Path('artifacts/rerank-onnx-110.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print('RERANK_COMPLETE',right,'/',len(tests),'top5',top5,'elapsed',result['elapsed'],flush=True)
if __name__=='__main__':main()
