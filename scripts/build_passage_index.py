#!/usr/bin/env python3
"""Build source-anchored passages for high-precision, entirely offline search.

Each record points to an exact Unicode character range in the *verbatim* law.
No answers or textual substitutions are generated, and original law ids stay intact.
"""
from __future__ import annotations
import sqlite3, re, sys
from pathlib import Path
from collections import Counter
from build_corpus import normalize

DB = Path(__file__).resolve().parents[1] / 'app/src/main/assets/rambam.sqlite'
BREAK = re.compile(r'[.!?׃;:\n]|[,،]')


def word_boundary(text: str, preferred: int, lo: int, hi: int) -> int:
    # End after a punctuation mark if present nearby, else a whitespace break.
    preferred = min(preferred, hi)
    spans = [m.end() for m in BREAK.finditer(text, lo, hi)
             if abs(m.end()-preferred) <= 80]
    if spans:
        return min(spans, key=lambda v: abs(v-preferred))
    spots = [i+1 for i in range(lo,hi) if text[i].isspace()]
    if spots:
        return min(spots, key=lambda v: abs(v-preferred))
    return hi


def split_passages(body: str) -> list[tuple[int,int]]:
    """Windows usually 130–220 chars, ~45-char overlap for contextual retrieval.

    Subwindows are refined in the Android reader to avoid highlighting a
    whole paragraph when only a clause supplies the answer.
    """
    if not body.strip(): return []
    n=len(body)
    if n<=210: return [(0,n)]
    out=[]; start=0
    while start<n:
        remain=n-start
        if remain<=215:
            if not out or out[-1][1]<n: out.append((start,n))
            break
        lo=start+115
        hi=min(n,start+245)
        end=word_boundary(body, start+190, lo,hi)
        if end<=start+100: end=min(n,start+180)
        out.append((start,end))
        next_start=max(start+95,end-45)
        if next_start>=n: break
        start=next_start
    return list(dict.fromkeys(out))


def main():
    if not DB.exists(): raise RuntimeError('No corpus: run build_corpus.py first')
    db=sqlite3.connect(DB)
    db.execute('PRAGMA journal_mode=DELETE')
    db.executescript('''
    DROP TABLE IF EXISTS passage_fts;
    DROP TABLE IF EXISTS passages;
    CREATE TABLE passages (id INTEGER PRIMARY KEY, law_id INTEGER NOT NULL,
      start INTEGER NOT NULL, end INTEGER NOT NULL, norm TEXT NOT NULL);
    CREATE INDEX passages_by_law ON passages(law_id);
    CREATE VIRTUAL TABLE passage_fts USING fts4(norm,tokenize=unicode61);
    CREATE TABLE passage_terms(term TEXT PRIMARY KEY, df INTEGER NOT NULL);
    ''')
    count=0
    docfreq=Counter()
    for law_id,body in db.execute('SELECT id,body FROM laws'):
        for a,b in split_passages(body):
            segment=normalize(body[a:b]);
            if not segment: continue
            cur=db.execute('INSERT INTO passages(law_id,start,end,norm) VALUES(?,?,?,?)',
                           (law_id,a,b,segment))
            db.execute('INSERT INTO passage_fts(rowid,norm) VALUES(?,?)',(cur.lastrowid,segment))
            docfreq.update(set(segment.split()))
            count+=1
    db.executemany("INSERT INTO passage_terms(term,df) VALUES(?,?)", docfreq.items())
    db.commit()
    assert db.execute('SELECT count(*) FROM passages').fetchone()[0]==count
    assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
    db.execute('ANALYZE')
    db.commit()
    print(f'PASSAGE_INDEX_READY passages={count} corpus_laws={db.execute("SELECT COUNT(*) FROM laws").fetchone()[0]} db_bytes={DB.stat().st_size}',flush=True)
    db.close()

if __name__=='__main__': main()
