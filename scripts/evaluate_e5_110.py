#!/usr/bin/env python3
"""External-model offline-compatibility evaluation. Does NOT alter Android until verified.
MIT model intfloat/multilingual-e5-small. The downloadable model is only used at CI
build time; no customer data is uploaded or sent to any online inference provider.
"""
import os,sys,json,time,sqlite3,re,unicodedata
from pathlib import Path
import numpy as np
from transformers import AutoTokenizer
from huggingface_hub import hf_hub_download
import onnxruntime as ort

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/'app/src/main/assets/rambam.sqlite'
MODEL='intfloat/multilingual-e5-small'

def unmark(t):
    return ''.join(c for c in unicodedata.normalize('NFKD',t) if unicodedata.category(c)!='Mn')

def encode(session,tokenizer,texts,batch_size=12):
    names={x.name for x in session.get_inputs()}
    vect=[];t0=time.monotonic()
    for start in range(0,len(texts),batch_size):
        blk=texts[start:start+batch_size]
        tokens=tokenizer(blk,padding=True,truncation=True,max_length=128,return_tensors='np')
        feed={k:np.asarray(v,dtype=np.int64) for k,v in tokens.items() if k in names}
        if 'token_type_ids' in names and 'token_type_ids' not in feed:
            feed['token_type_ids']=np.zeros_like(feed['input_ids'],dtype=np.int64)
        arr=session.run(None,feed)[0].astype(np.float32)
        mask=feed['attention_mask'][...,None]
        mean=(arr*mask).sum(axis=1)/np.maximum(mask.sum(axis=1),1)
        mean/=np.maximum(np.linalg.norm(mean,axis=1,keepdims=True),1e-6)
        vect.append(mean)
        if (start+batch_size)%2400<batch_size:
            print('EMBED_PROGRESS',min(start+batch_size,len(texts)),'/',len(texts),'elapsed',int(time.monotonic()-t0),flush=True)
    return np.concatenate(vect)

def main():
    t0=time.monotonic()
    c=sqlite3.connect(DB)
    cases=json.load(open(ROOT/'scripts/quality_full_110.json'))
    lines=c.execute('select p.law_id, l.body, p.start,p.end from passages p join laws l on l.id=p.law_id order by p.id').fetchall()
    print('PASSAGES',len(lines),flush=True)
    print('MODEL_DOWNLOAD_BEGIN',MODEL,flush=True)
    path=hf_hub_download(repo_id=MODEL,filename='onnx/model_qint8_avx512_vnni.onnx')
    tokenizer=AutoTokenizer.from_pretrained(MODEL)
    opts=ort.SessionOptions();opts.intra_op_num_threads=2;opts.inter_op_num_threads=1
    sess=ort.InferenceSession(path,sess_options=opts,providers=['CPUExecutionProvider'])
    print('MODEL_LOADED',[(x.name,x.shape) for x in sess.get_inputs()],flush=True)
    vectors=encode(sess,tokenizer,['passage: '+unmark(body[a:b]) for _,body,a,b in lines])
    queries=encode(sess,tokenizer,['query: '+q['question'] for q in cases],batch_size=12)
    lawIds=np.array([lawId for lawId,_,_,_ in lines],np.int32)
    best=[];passed=0;top5=0
    section={id:(sec,ch,hl) for id,sec,ch,hl in c.execute('select id,section,chapter,halakhah from laws')}
    for i,q in enumerate(cases):
        similarities=vectors@queries[i]
        order=np.argsort(-similarities)
        seen=set();chosen=[]
        for j in order:
            law=int(lawIds[j]);
            if law in seen:continue
            seen.add(law);chosen.append((law,round(float(similarities[j]),4)))
            if len(chosen)>=10:break
        expected=('משנה תורה, הלכות '+q['section'],q['chapter'],q['law'])
        ranks=[k+1 for k,(law,_) in enumerate(chosen) if section[law]==expected]
        passed+=bool(ranks and ranks[0]==1);top5+=bool(ranks and ranks[0]<=5)
        print(f"E5_CASE {i+1:03d} {'PASS' if ranks and ranks[0]==1 else 'FAIL'} expected={expected} top={section[chosen[0][0]] if chosen else None} rank={ranks[0] if ranks else '>10'}",flush=True)
        best.append(dict(question=q['question'],expected=q,top5=[dict(source=section[law],score=score) for law,score in chosen[:5]]))
    report={'model':MODEL,'questions':len(cases),'top1':passed,'top5':top5,'elapsed_seconds':round(time.monotonic()-t0,1),'source':'verbatim Rambam', 'results':best}
    Path('artifacts').mkdir(exist_ok=True)
    Path('artifacts/e5-model-probe-110.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print('E5_COMPLETE',passed,'/',len(cases),'top5',top5,'duration',report['elapsed_seconds'],flush=True)

if __name__=='__main__':main()
