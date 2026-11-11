#!/usr/bin/env python3
"""Independent invariants for source offsets and recall index."""
import sqlite3
from pathlib import Path
from build_corpus import normalize
p=Path(__file__).resolve().parents[1]/'app/src/main/assets/rambam.sqlite'
db=sqlite3.connect(p)
assert db.execute('pragma integrity_check').fetchone()[0]=='ok'
assert db.execute('select count(*) from laws').fetchone()[0] >= 15000
n=db.execute('select count(*) from passages').fetchone()[0]
assert n>=35000,n
for id,body,a,b,norm in db.execute('select p.id,l.body,p.start,p.end,p.norm from passages p join laws l on p.law_id=l.id'):
    assert 0<=a<b<=len(body),(id,a,b,len(body))
    assert normalize(body[a:b])==norm,id
for q in ['חנוכה','מזונ','מלאכה','סוכה','מעשר','אבדה']:
    assert db.execute('select count(*) from passage_fts where passage_fts match ?', (q+'*',)).fetchone()[0]>0,q
assert db.execute('select count(*) from passage_terms').fetchone()[0]>30000
print('PASSAGE_INDEX_VERIFIED',n)
