#!/usr/bin/env python3
"""Validate the 100 natural questions against the original source corpus.
This intentionally DOES NOT claim Android retrieval or highlight success.
An app/device run must separately prove 100/100 top-1 and answer-span results.
"""
from __future__ import annotations
import json, sqlite3, re, unicodedata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "scripts/quality_natural_100.json"
DB = ROOT / "app/src/main/assets/rambam.sqlite"
REPORT = ROOT / "artifacts/natural_100_gold_validation.json"

def normalized(value: str) -> str:
    value = unicodedata.normalize("NFKC", value)
    value = re.sub(r"[\u0591-\u05c7]", "", value)
    value = value.translate(str.maketrans("ךםןףץ","כמנפצ"))
    return " ".join(re.sub(r"[^\w]+", " ", value).split()).lower()

def main() -> None:
    questions = json.loads(DATA.read_text(encoding="utf-8"))
    assert isinstance(questions,list) and len(questions)==100, "Exactly 100 questions required"
    assert len({item["question"] for item in questions})==100, "Repeated question"
    con=sqlite3.connect(DB)
    corpus_size=con.execute("SELECT COUNT(*) FROM laws").fetchone()[0]
    assert corpus_size >= 15000, f"Incomplete corpus {corpus_size}"
    rows=[]
    for i,item in enumerate(questions,1):
        section="משנה תורה, הלכות "+item["section"]
        law=con.execute(
            "SELECT id,body FROM laws WHERE section=? AND chapter=? AND halakhah=?",
            (section,item["chapter"],item["law"])).fetchone()
        has_source=law is not None and bool(law[1].strip())
        phrase=item.get("highlight_contains","")
        has_phrase=(not phrase) or (has_source and normalized(phrase) in normalized(law[1]))
        rows.append({"case":i,"tier":item.get("tier"),"query":item["question"],
                     "source":f'{item["section"]} {item["chapter"]}:{item["law"]}',
                     "reference_found":bool(has_source),
                     "anchor_checked":bool(phrase),
                     "anchor_in_original":bool(has_phrase),
                     "origin":item.get("origin","")})
    report={
        "test_type":"gold-source-integrity-only",
        "question_count":len(rows),"corpus_laws":corpus_size,
        "gold_sources_verified":sum(r["reference_found"] for r in rows),
        "highlight_anchors_present":sum(r["anchor_checked"] for r in rows),
        "highlight_anchors_verified":sum(r["anchor_checked"] and r["anchor_in_original"] for r in rows),
        "android_retrieval_top1":None,
        "android_highlight_accuracy":None,
        "note":"Neither 100/100 retrieval nor device highlighting is established by this source-only validation.",
        "cases":rows,
    }
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(f'GOLD_SOURCE_VALIDATION references={report["gold_sources_verified"]}/100 anchors={report["highlight_anchors_verified"]}/{report["highlight_anchors_present"]} actual_android_accuracy=UNTESTED',flush=True)
    if not all(r["reference_found"] and r["anchor_in_original"] for r in rows):
        raise SystemExit("Missing gold source or incorrect source-text anchor")
    con.close()

if __name__=="__main__":
    main()
