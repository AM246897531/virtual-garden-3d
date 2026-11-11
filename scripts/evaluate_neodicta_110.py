#!/usr/bin/env python3
"""Isolated OFFLINE-capable Hebrew model evaluation; never ships unverified weights.
Evaluates all 110 frozen real questions against source-anchored excerpts, no
reference leakage into retrieval or candidate generation. CC-BY-4.0 model.
"""
from __future__ import annotations
import json,time,sqlite3,unicodedata,sys,os
from pathlib import Path
import numpy as np
import torch
from sklearn.feature_extraction.text import TfidfVectorizer
from sentence_transformers import SentenceTransformer

ROOT=Path(__file__).resolve().parents[1]
MODEL='dicta-il/neodictabert-bilingual-embed'

def unmark(s):
    return ''.join(c for c in unicodedata.normalize('NFKD',s) if unicodedata.category(c)!='Mn')

def main():
    started=time.monotonic();torch.set_num_threads(4)
    db=sqlite3.connect(ROOT/'app/src/main/assets/rambam.sqlite')
    tests=json.loads((ROOT/'scripts/quality_full_110.json').read_text())
    rows=db.execute('SELECT p.id,p.law_id,l.section,l.chapter,l.halakhah,l.body,p.start,p.end,p.norm FROM passages p JOIN laws l ON l.id=p.law_id ORDER BY p.id').fetchall()
    clean=[unmark(r[5][r[6]:r[7]]) for r in rows]
    texts=[r[8]+' '+r[2].replace('משנה תורה, הלכות ','') for r in rows]
    print('MODEL',MODEL,'SAMPLES',len(rows),'QUESTIONS',len(tests),flush=True)
    tf=TfidfVectorizer(analyzer='char',ngram_range=(2,4),max_features=130000,dtype=np.float32,sublinear_tf=True,min_df=2)
    x=tf.fit_transform(texts);qs=tf.transform([q['question'] for q in tests])
    q_candidates=[]; unique=set()
    for q in qs:
        ranked=(q@x.T).tocsr(); positions=np.argsort(-ranked.data)[:80]
        ids=[int(ranked.indices[i]) for i in positions]
        q_candidates.append(ids);unique.update(ids)
    unique=sorted(unique)
    print('CHAR_CANDIDATES_UNIQUE',len(unique), 'preparation_sec',round(time.monotonic()-started,1),flush=True)
    print('MODEL_LOADING',flush=True)
    model=SentenceTransformer(MODEL,device='cpu',trust_remote_code=True)
    # Distinguish source passages from queries; respect model's native prefixes.
    all_embeddings={};size=24
    for start in range(0,len(unique),size):
        chunk=unique[start:start+size]
        emb=model.encode(['passage: '+clean[j] for j in chunk],batch_size=8,normalize_embeddings=True,show_progress_bar=False,convert_to_numpy=True)
        for j,v in zip(chunk,emb):all_embeddings[j]=v
        if start%240<24:
            print('EMBEDDED',min(start+size,len(unique)),'/',len(unique),'seconds',round(time.monotonic()-started,1),flush=True)
    question_embeddings=model.encode(['query: '+q['question'] for q in tests],batch_size=8,normalize_embeddings=True,show_progress_bar=False,convert_to_numpy=True)
    passed=0;top5=0;results=[]
    for i,q in enumerate(tests):
        ids=q_candidates[i]
        if not ids:
            results.append(dict(question=q['question'],rank=None));continue
        values=np.stack([all_embeddings[j] for j in ids])@question_embeddings[i]
        order=np.argsort(-values)
        laws=[];seen=set()
        for z in order:
            row=rows[ids[int(z)]];law=(row[2],row[3],row[4]);
            if law in seen:continue
            seen.add(law);laws.append((law,round(float(values[z]),5)))
            if len(laws)>=10:break
        exp=('משנה תורה, הלכות '+q['section'],q['chapter'],q['law'])
        match=[j+1 for j,(law,_) in enumerate(laws) if law==exp]
        rank=match[0] if match else None
        passed+=rank==1;top5+=rank is not None and rank<=5
        print('NEO_CASE',i+1,'PASS' if rank==1 else 'FAIL','expected',exp,'top',laws[0][0] if laws else None,'rank',rank,flush=True)
        results.append(dict(question=q['question'],expected=exp,top5=laws[:5],rank=rank))
    report=dict(model=MODEL,license='CC-BY-4.0',benchmark_size=len(tests),top1=passed,top5=top5,total_sec=round(time.monotonic()-started,1),results=results)
    Path('artifacts').mkdir(exist_ok=True)
    Path('artifacts/neodicta-110-report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2))
    print('NEO_COMPLETE',passed,'/',len(tests),'top5',top5,'seconds',report['total_sec'],flush=True)
if __name__=='__main__':main()
