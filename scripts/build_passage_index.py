#!/usr/bin/env python3
"""Build source-anchored passages for high-precision, entirely offline search.

Each record points to an exact Unicode character range in the *verbatim* law.
No answers or textual substitutions are generated, and original law ids stay intact.
"""
from __future__ import annotations
import sqlite3, re, sys
from pathlib import Path
from collections import Counter,defaultdict
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
    if n<=320: return [(0,n)]
    out=[]; start=0
    while start<n:
        remain=n-start
        if remain<=320:
            if not out or out[-1][1]<n: out.append((start,n))
            break
        lo=start+145
        hi=min(n,start+355)
        end=word_boundary(body, start+290, lo,hi)
        if end<=start+130: end=min(n,start+275)
        out.append((start,end))
        next_start=max(start+120,end-85)
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
    DROP TABLE IF EXISTS chapter_leads;
    DROP TABLE IF EXISTS passage_terms;
    DROP TABLE IF EXISTS section_terms;
    DROP TABLE IF EXISTS section_totals;
    DROP TABLE IF EXISTS global_terms;
    CREATE TABLE passages (id INTEGER PRIMARY KEY, law_id INTEGER NOT NULL,
      start INTEGER NOT NULL, end INTEGER NOT NULL, norm TEXT NOT NULL);
    CREATE INDEX passages_by_law ON passages(law_id);
    CREATE VIRTUAL TABLE passage_fts USING fts4(norm,tokenize=unicode61);
    CREATE TABLE passage_terms(term TEXT PRIMARY KEY, df INTEGER NOT NULL);
    CREATE TABLE section_terms(section TEXT NOT NULL, term TEXT NOT NULL, tf INTEGER NOT NULL, PRIMARY KEY(section,term)) WITHOUT ROWID;
    CREATE TABLE section_totals(section TEXT PRIMARY KEY, total INTEGER NOT NULL);
    CREATE TABLE global_terms(term TEXT PRIMARY KEY, tf INTEGER NOT NULL);
    CREATE TABLE chapter_leads(section TEXT NOT NULL, chapter INTEGER NOT NULL,
      intro TEXT NOT NULL, PRIMARY KEY(section, chapter)) WITHOUT ROWID;
    ''')
    count=0
    docfreq=Counter()
    section_counts=defaultdict(Counter)
    global_counts=Counter()
    section_lengths=Counter()
    for section,norm in db.execute("SELECT section,norm FROM laws"):
        tokens=norm.split()
        section_counts[section].update(tokens)
        global_counts.update(tokens)
        section_lengths[section]+=len(tokens)
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
    db.executemany("INSERT INTO section_terms(section,term,tf) VALUES(?,?,?)",
        ((sec,term,n) for sec,terms in section_counts.items() for term,n in terms.items()))
    db.executemany("INSERT INTO section_totals(section,total) VALUES(?,?)", section_lengths.items())
    db.executemany("INSERT INTO global_terms(term,tf) VALUES(?,?)", global_counts.items())
    for section, chapter in db.execute('SELECT section,chapter FROM laws GROUP BY section,chapter'):
        opening = [row[0] for row in db.execute(
            'SELECT norm FROM laws WHERE section=? AND chapter=? ORDER BY halakhah LIMIT 3',
            (section,chapter))]
        title = section.split('הלכות ',1)[-1]
        intro = (title+' ')*3+' '.join(opening)
        db.execute('INSERT INTO chapter_leads(section,chapter,intro) VALUES(?,?,?)',
                   (section,chapter,intro))
    db.commit()
    assert db.execute('SELECT COUNT(*) FROM chapter_leads').fetchone()[0]>=1000
    assert db.execute('SELECT count(*) FROM passages').fetchone()[0]==count
    assert db.execute('PRAGMA integrity_check').fetchone()[0]=='ok'
    db.execute('ANALYZE')
    db.commit()
    print(f'PASSAGE_INDEX_READY passages={count} corpus_laws={db.execute("SELECT COUNT(*) FROM laws").fetchone()[0]} db_bytes={DB.stat().st_size}',flush=True)
    db.close()

if __name__=='__main__': main()
