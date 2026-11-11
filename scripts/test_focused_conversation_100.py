#!/usr/bin/env python3
"""No-build regression: verbose Hebrew wrapping must preserve all legal facts.

This is a Python logic mirror of SearchLanguage.evidenceSegments and
focusedEvidenceQuery. It tests query signal retention, not Android accuracy.
"""
from __future__ import annotations
import json
import re
from pathlib import Path
from build_corpus import normalize

ROOT=Path(__file__).resolve().parents[1]
GOLD=ROOT/"scripts/quality_natural_100.json"
LANGUAGE_SOURCE=ROOT/"app/src/main/java/app/mishnehtorah/SearchLanguage.kt"
INTRO="אני מנסה להבין מקרה הלכתי מעשי ושואל מפני שאינני יודע מה המקור הנכון במפורש במשנה תורה לרמבם. "
ENDING=" אני מבקש למצוא את ההלכה המדויקת בלשון הרמבם ולא הסבר משוער או מקור קשור באופן כללי."
PREFIXES={"original":("",""),"prefix":(INTRO,""),
          "suffix":("",ENDING),"wrapped":(INTRO,ENDING)}

def stop_terms():
    source=LANGUAGE_SOURCE.read_text(encoding="utf-8")
    raw=source.split("private val stop = setOf(",1)[1].split(")",1)[0]
    return {normalize(term) for term in re.findall(r'"([^"\n]+)"',raw)}

def focus(question,stop):
    if len(question)<75:return question
    chunks=[value.strip() for value in re.split(r"[.!?;׃\n]+",question)]
    chunks=[value for value in chunks if 9<=len(value)<=950]
    if len(chunks)<2:return question
    scored=[]
    for index,chunk in enumerate(chunks):
        n=normalize(chunk)
        words=[w for w in n.split(" ") if len(w)>=3 and w not in stop]
        meaningful=sum(len(w)>=4 for w in words)
        location=question.find(chunk)
        suffix=question[location+len(chunk):].lstrip() if location>=0 else ""
        is_question=(suffix.startswith("?") or n.startswith(("מה ","מתי ","האם ","באיזה "))
                     or " שכחתי " in n)
        boilerplate=any(normalize(z) in n for z in (
            "אני מבקש למצוא","אני מנסה להבין",
            "המקור הנכון","ההלכה המדויקת","משנה תורה לרמב"))
        scored.append((meaningful+6*int(is_question)-12*int(boilerplate),index,chunk))
    scored.sort(key=lambda item:(-item[0],item[1]))
    selected=[(i,chunk) for score,i,chunk in scored[:3] if score>0]
    return " ".join(chunk for _,chunk in sorted(selected)) or question

def relevant_terms(question,stop):
    return {word for word in normalize(question).split() if len(word)>=3 and word not in stop}

def main():
    cases=json.loads(GOLD.read_text(encoding="utf-8"))
    assert len(cases)==100
    stop=stop_terms()
    for case,pair in PREFIXES.items():
        successes=0
        missing=[]
        for idx,item in enumerate(cases,1):
            original=item["question"]
            wrapped=pair[0]+original+pair[1]
            query=focus(wrapped,stop)
            legal=relevant_terms(original,stop)
            preserved=relevant_terms(query,stop)
            if legal.issubset(preserved):successes+=1
            else:missing.append((idx,sorted(legal-preserved)))
        print(f"FOCUSED_QUERY_REGRESSION {case} legal_concepts={successes}/100 "
              f"losses={missing}",flush=True)
        assert successes==100, f"Lost source-relevant terms in {case}"
    print("FOCUSED_QUERY_SIGNAL_400 PASS. Android search and turquoise highlighting untested.")

if __name__=="__main__":main()
