#!/usr/bin/env python3
"""Offline-first, reference-blind candidate-recall audit for real Rambam questions.

Candidate retrieval NEVER reads expected book, chapter or halakhah. The answer
labels are accessed only after candidates are computed, to measure whether a
correct source is in the pool. Candidate recall is NOT top-1 answer accuracy.
"""
from __future__ import annotations
import json, re, sqlite3, unicodedata, functools, time
from pathlib import Path
from collections import defaultdict
import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

ROOT=Path(__file__).resolve().parents[1]
DB=ROOT/'app/src/main/assets/rambam.sqlite'

@functools.lru_cache(maxsize=280000)
def morph(word: str) -> str:
    if len(word)>=5 and word[0] in 'והבכמלשית': word=word[1:]
    if len(word)>=5 and word.endswith(('ים','ות','יו','יה')):word=word[:-2]
    if len(word)>=5 and word.endswith('ה'):word=word[:-1]
    if len(word)>=4:word=word[0]+''.join(c for c in word[1:-1] if c not in 'וי')+word[-1]
    return word

def unmark(text: str) -> str:
    return ''.join(c for c in unicodedata.normalize('NFKD',text) if unicodedata.category(c)!='Mn')

def morphemes(text: str) -> str:
    return ' '.join(morph(w) for w in re.findall(r'[א-ת]+',unmark(text)) if len(w)>=3)

def top_laws(source, questions, records, n=200):
    vec=TfidfVectorizer(analyzer='char',ngram_range=(2,4),max_features=130000,
                        min_df=2,dtype=np.float32,sublinear_tf=True)
    matrix=vec.fit_transform(source)
    queries=vec.transform(questions)
    scores=(queries@matrix.T).tocsr()
    results=[]
    for i in range(len(questions)):
        row=scores.getrow(i)
        order=np.argsort(-row.data)[:1300]
        seen=set();candidates=[]
        for j in order:
            lid=records[int(row.indices[j])]
            if lid in seen:continue
            seen.add(lid);candidates.append(lid)
            if len(candidates)>=n:break
        results.append(candidates)
    return results

def main():
    t0=time.monotonic()
    db=sqlite3.connect(DB)
    questions=json.loads((ROOT/'scripts/quality_full_110.json').read_text())
    # Do not access questions[i]['section'/'chapter'/'law'] before candidate generation.
    query_texts=[q['question'] for q in questions]
    passages=db.execute('SELECT p.law_id,p.norm,l.section FROM passages p '
                        'JOIN laws l ON l.id=p.law_id ORDER BY p.id').fetchall()
    ids=[r[0] for r in passages]
    literal_docs=[unmark(text)+' '+unmark(section.split('הלכות ')[-1])
                  for _,text,section in passages]
    print('PASSAGE_COUNT',len(passages),'QUESTION_COUNT',len(questions),flush=True)
    literal=top_laws(literal_docs,[unmark(q) for q in query_texts],ids)
    print('LITERAL_COMPLETE',round(time.monotonic()-t0,1),flush=True)
    morphology=top_laws([morphemes(doc) for doc in literal_docs],
                        [morphemes(q) for q in query_texts],ids)
    print('MORPHOLOGY_COMPLETE',round(time.monotonic()-t0,1),flush=True)
    chapters=defaultdict(list)
    for lid,section,ch,law,norm in db.execute('SELECT id,section,chapter,halakhah,norm FROM laws '
                                              'ORDER BY section,chapter,halakhah'):
        chapters[(section,ch)].append((lid,law,norm))
    chapter_names=list(chapters)
    intros=[(section.split('הלכות ')[-1]+' ')*3+' '.join(lawtext for _,_,lawtext in chapters[(section,ch)][:3])
            for section,ch in chapter_names]
    chvec=TfidfVectorizer(analyzer='char',ngram_range=(2,5),max_features=150000,
                          min_df=2,dtype=np.float32,sublinear_tf=True)
    chapter_matrix=chvec.fit_transform(intros)
    chapter_q=chvec.transform([unmark(s) for s in query_texts])
    rank=np.argsort(-(chapter_q@chapter_matrix.T).toarray(),axis=1)
    source_pools=[]
    for i in range(len(questions)):
        pool=set(literal[i][:200]);pool.update(morphology[i][:200])
        for chapter_ix in rank[i][:20]:
            pool.update(law_id for law_id,_,_ in chapters[chapter_names[int(chapter_ix)]])
        source_pools.append(pool)
    # Evaluation only. Never inject the target reference into source_pools.
    correct=0;details=[]
    for i,case in enumerate(questions):
        expected=db.execute('SELECT id FROM laws WHERE section=? AND chapter=? AND halakhah=?',
                            ('משנה תורה, הלכות '+case['section'],case['chapter'],case['law'])).fetchone()
        if expected is None:raise RuntimeError(f'Missing source label #{i+1}')
        hit=expected[0] in source_pools[i]
        correct+=bool(hit)
        details.append(dict(number=i+1,question=case['question'],found=hit,
                            candidates=len(source_pools[i])))
        if not hit:print('SOURCE_NOT_RETRIEVED',i+1,case['question'],flush=True)
    result=dict(metric='source candidate recall (NOT accuracy)',questions=len(questions),
                reached=correct,elapsed_seconds=round(time.monotonic()-t0,1),cases=details,
                avg_candidates=int(np.mean([len(c) for c in source_pools])))
    output=ROOT/'artifacts';output.mkdir(exist_ok=True)
    (output/'multi-recall-110.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    print('MULTI_RECALL_COMPLETE',correct,'/',len(questions),'avg_pool=',result['avg_candidates'],
          'seconds=',result['elapsed_seconds'],flush=True)
    if correct!=len(questions):raise SystemExit(1)

if __name__=='__main__':main()
