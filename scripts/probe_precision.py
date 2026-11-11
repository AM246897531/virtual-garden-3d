#!/usr/bin/env python3
"""Local mirror of lexical stage of Android passage search; NOT Android results."""
import re,json,sqlite3, math,statistics,sys,time
from functools import lru_cache
from collections import Counter,defaultdict
from pathlib import Path
from build_corpus import normalize
P=Path(__file__).resolve().parents[1]
text=(P/'app/src/main/java/app/mishnehtorah/SearchLanguage.kt').read_text()
ss=text.split('private val stop = setOf(',1)[1].split(')',1)[0]
stop={normalize(x) for x in re.findall(r'"([^"\n]+)"',ss)}
syns={normalize(a):[normalize(x) for x in re.findall(r'"([^"\n]+)"',b)] for a,b in re.findall(r'"([^"\n]+)" to listOf\((.*?)\)',text,re.S)}
print('syns',len(syns),'stop',len(stop))
db=sqlite3.connect(P/'app/src/main/assets/rambam.sqlite')
N=db.execute('select count(*) from passages').fetchone()[0]
ids=db.execute('select id,section from laws').fetchall(); section={i:normalize(s) for i,s in ids}
secraw={i:s for i,s in ids};secCounts=defaultdict(Counter);glob=Counter();secLens=Counter()
for sec,norm in db.execute('select section,norm from laws'):
 ws=norm.split();secCounts[sec].update(ws);glob.update(ws);secLens[sec]+=len(ws)
corpusLen=sum(secLens.values())
df={t:d for t,d in db.execute('select term,df from passage_terms')}

@lru_cache(maxsize=200000)
def stem(s):
 w=s
 if len(w)>5 and w[0] in 'בכלמוהש': w=w[1:]
 if len(w)>5 and w[0] in 'בכלמוהש' and s.startswith('ו'): w=w[1:]
 if len(w)>5 and w.endswith(('ימ','ות')):w=w[:-2]
 elif len(w)>5 and w.endswith(('יה','ה')):w=w[:-1]
 return w

@lru_cache(maxsize=500000)
def skeleton(w):
 z=w;r=0
 while len(z)>=5 and r<2 and z[0] in 'בכלמוהש':z=z[1:];r+=1
 if len(z)>=5 and z.endswith(('ימ','ות','יה','יו')):z=z[:-2]
 elif len(z)>=5 and z.endswith('ה'):z=z[:-1]
 if len(z)>=5:z=z[0]+''.join(c for c in z[1:-1] if c not in 'יו')+z[-1]
 return z

@lru_cache(maxsize=500000)
def matches(w,c):
 if w==c:return 1
 if len(w)>=4 and c.startswith(w):return .91
 if len(c)>=4 and w.startswith(c) and len(w)-len(c)<=2:return .85
 a,b=stem(w),stem(c)
 if len(a)>=4 and a==b:return .84
 if len(a)>=4 and len(b)>=4 and (a.startswith(b) or b.startswith(a)) and abs(len(a)-len(b))<=2:return .67
 if len(skeleton(w))>=3 and skeleton(w)==skeleton(c):return .58
 return 0

def analyze(q):
 q=normalize(q.replace('ר״ח','ראש חודש').replace('ר"ח','ראש חודש'))
 words=q.split();out=[];seen=set()
 for w in words:
  if len(w)<3 or w in stop:continue
  key=w
  if w not in syns:
   without=w
   for _ in range(3):
    if len(without)>=5 and without[0] in 'בהולמשכ':
     without=without[1:]
     if without in syns:key=without
  alts=list(dict.fromkeys([x for x in syns.get(key,[])+[w,key] if len(x)>=3 and ' ' not in x]))[:8]
  st=stem(w)
  if st in seen:continue
  seen.add(st)
  weight=.48 if w in {'מתי','זמנ','מקום','כמה','מספר','חייב','אסור'} else 1
  values=[df.get(a,10**8) for a in alts if df.get(a,0)>0]
  f=min(values or [60]);idf=(max(1.1,min(6.2,math.log((N+10)/(f+1)))) if values else .65)*weight
  out.append((w,alts,idf))
  if len(out)>=11:break
 return out

def grp(term):
 alts=term[1][:5]
 return '('+' OR '.join(a+'*' for a in alts)+')' if len(alts)>1 else alts[0]+'*'

def profile(qterms):
 rankings={}
 for sec,C in secCounts.items():
  score=0
  for w,alts,importance in qterms:
   c=max([C.get(a,0) for a in alts] or [0])
   g=sum(glob.get(a,0) for a in alts)
   if c and g:
    score+=math.log(1+(c/secLens[sec])*corpusLen/g)*importance
  rankings[sec]=score
 ma=max(rankings.values()) or 1
 return {s:v/ma for s,v in rankings.items()}

def query(q, maxResults=5):
 terms=analyze(q)
 if not terms:return []
 ordered=sorted(terms,key=lambda x:-x[2])[:8]
 groups=[grp(x) for x in ordered]
 candidates=set()
 for i in range(min(4,len(groups))):
  for j in range(i+1,min(i+3,len(groups))):
   try:candidates.update(i[0] for i in db.execute('select rowid from passage_fts where passage_fts match ? limit 1000',(groups[i]+' AND '+groups[j],)))
   except sqlite3.Error:pass
   if len(candidates)>=3500:break
  if len(candidates)>=3500:break
 for group in groups[:8]:
  try:candidates.update(i[0] for i in db.execute(f'select rowid from passage_fts where passage_fts match ? limit {800 if len(candidates)>6500 else 2400}',(group,)))
  except sqlite3.Error:pass
  if len(candidates)>14500:break
 if len(groups)>=2 and len(candidates)<10000:
  try:candidates.update(i[0] for i in db.execute('select rowid from passage_fts where passage_fts match ? limit 1800',(' OR '.join(groups[:6]),)))
  except sqlite3.Error:pass
 scored={}; total=sum(t[2] for t in terms)
 aff=profile(terms)
 for batch in (list(candidates)[i:i+250] for i in range(0,len(candidates),250)):
  ph=','.join('?'*len(batch))
  for pid,lid,a,b,p in db.execute(f'select id,law_id,start,end,norm from passages where id in ({ph})',batch):
   tokens=[w for w in p.split() if len(w)>=3]
   matched=0;covered=0;sectioncovered=0
   for w,alts,wght in terms:
    m=max((matches(alt,t) for alt in alts for t in tokens),default=0)
    if m>0:covered+=wght*m;matched+=1
    else:
     m=max((matches(alt,t) for alt in alts for t in section[lid].split()),default=0)
     sectioncovered+=wght*m
   coverage=covered/total
   if matched<(1 if len(terms)==1 else 2) or coverage<(.40 if len(terms)<3 else .38):continue
   score=coverage*110+matched*2.5+sectioncovered/total*6-(len(p)-180 if len(p)>180 else 0)*.012
   if ("מותר" in normalize(q) or "אסור" in normalize(q)) and any(k in p for k in ("אסור","מותר","מתר")):score+=20
   if 'כמה' in normalize(q).split():
    nums={'אחד','אחת','שני','שנימ','שתימ','שלוש','שלשה','שלושת','ארבע','חמש','שש','שבע','שמונה','תשע','עשר','עשרימ','שלשימ'}
    anchors=[x for x in terms if x[0] not in {'כמה','מספר','מנינ'} and len(x[0])>=4][:1]
    pwords=p.split()
    nidx=[i for i,w in enumerate(pwords) if w in nums]
    if nidx:
     close=any(any(matches(alt,t)>=.84 for _,alts,_ in anchors for alt in alts for t in pwords[max(0,i-3):i+4]) for i in nidx)
     score+=22 if close else 3
   sec=secraw[lid];score+=40*aff.get(sec,0)
   title=normalize(sec.split('הלכות ',1)[-1]).split()
   score+=min(23,sum(10*wt for w,alts,wt in terms if any(matches(a,t)>.65 for a in alts for t in title)))
   old=scored.get(lid)
   if old is None or score>old[0]:scored[lid]=(score,matched,coverage,a,b)
 return sorted(scored.items(),key=lambda x:-x[1][0])[:maxResults]

def main():
 for datafile in ('quality_unseen_30.json',):
  arr=json.load(open(P/'scripts'/datafile));right=0
  start=time.time()
  for i,q in enumerate(arr,1):
   tops=query(q['question'])
   lawid=tops[0][0] if tops else -1
   got=db.execute('select section,chapter,halakhah from laws where id=?',(lawid,)).fetchone()
   good=bool(got and got[0].endswith('הלכות '+q['section']) and got[1]==q['chapter'] and got[2]==q['law'])
   right+=good
   print(('%02d'%i),'PASS' if good else 'FAIL',q['question'][:38], '=>',str(got)[:85], 'expect',q['section'],q['chapter'],q['law'],'cov',round(tops[0][1][2],2) if tops else None,flush=True)
  print('TOTAL',datafile,right,'/',len(arr),'seconds',round(time.time()-start,1),flush=True)
if __name__=='__main__':main()
