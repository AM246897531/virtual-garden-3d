#!/usr/bin/env python3
"""Produce independently checkable original UTF-16 gold evidence offsets.
This ONLY prepares a debug test asset from authentic Rambam text. It never
supplies references or answer snippets to the production retrieval engine.
"""
from __future__ import annotations
import json
import sqlite3
import unicodedata
from pathlib import Path
from build_corpus import normalize

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/"scripts/quality_natural_100.json"
DB=ROOT/"app/src/main/assets/rambam.sqlite"
DEBUG=ROOT/"app/src/debug/assets/quality_natural_100.json"
REPORT=ROOT/"artifacts/gold_answer_offsets_100.json"
FINAL_FOLD=str.maketrans("ךםןףץ","כמנפצ")

def normalized_with_offsets(text: str):
    characters=[]
    origins=[]
    pending_space=False
    space_index=0
    for source_index,char in enumerate(text):
        for value in unicodedata.normalize("NFKC",char):
            if "\u0591"<=value<="\u05c7":
                continue
            if value in "\u05be\u05c0\u05c3\u05f3\u05f4":
                pending_space=True
                space_index=source_index
                continue
            if value.isalnum() or value=="_":
                if pending_space and characters:
                    characters.append(" ")
                    origins.append(space_index)
                pending_space=False
                characters.append(value.translate(FINAL_FOLD).lower())
                origins.append(source_index)
            else:
                pending_space=True
                space_index=source_index
    return "".join(characters),origins

def to_utf16(text: str, pos: int):
    return len(text[:pos].encode("utf-16-le"))//2

def main():
    cases=json.loads(SOURCE.read_text(encoding="utf-8"))
    assert len(cases)==100 and len({v["question"] for v in cases})==100
    db=sqlite3.connect(DB)
    report=[]
    updated=[]
    for case_index,item in enumerate(cases,1):
        row=db.execute(
            "SELECT body FROM laws WHERE section=? AND chapter=? AND halakhah=?",
            ("משנה תורה, הלכות "+item["section"],item["chapter"],item["law"])
        ).fetchone()
        assert row, f"Missing genuine law for case {case_index}"
        original=row[0]
        anchor=normalize(item["highlight_contains"])
        assert anchor, f"Unspecified answer evidence in case {case_index}"
        text,source_indices=normalized_with_offsets(original)
        assert text==normalize(original), f"Offset normalizer mismatch for case {case_index}"
        at=text.find(anchor)
        assert at>=0, f"Answer evidence not literally in correct source for case {case_index}"
        begin=source_indices[at]
        end=source_indices[at+len(anchor)-1]+1
        assert 0<=begin<end<=len(original)
        assert anchor in normalize(original[begin:end])
        ustart,uend=to_utf16(original,begin),to_utf16(original,end)
        out=dict(item)
        out["highlight_start_utf16"]=ustart
        out["highlight_end_utf16"]=uend
        updated.append(out)
        report.append({"case":case_index,"original_start_utf16":ustart,
                       "original_end_utf16":uend,"anchor_length_utf16":uend-ustart})
    DEBUG.parent.mkdir(parents=True,exist_ok=True)
    DEBUG.write_text(json.dumps(updated,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    summary={"type":"source-only gold offset derivation, NOT Android highlight result",
             "questions":len(updated),"sources_with_gold_offsets":len(report),
             "max_gold_span_utf16":max(q["anchor_length_utf16"] for q in report),
             "cases":report}
    REPORT.write_text(json.dumps(summary,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print("RANGE_GOLD_100_COMPLETE sources=100/100 exact_original_offsets=100/100 "
          f'max_anchor_chars={summary["max_gold_span_utf16"]} '
          "android_highlight_accuracy=UNTESTED",flush=True)

if __name__=="__main__":main()
