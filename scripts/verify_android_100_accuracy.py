#!/usr/bin/env python3
"""Strict real-device accuracy gate for an EXACT single Android QA execution.

Supports Q100, Q40 and Q500, with the latter containing 140 existing
free-form and 360 explicitly source-derived synthetic questions.
Never combine cases across runs, ignore missing metrics, or treat an in-bounds
highlight as a correct source answer. No APK compilation occurs here.

Example after an independently authorized device test:
    python scripts/verify_android_100_accuracy.py adb.log
    python scripts/verify_android_100_accuracy.py adb.log --prefix Q40 --total 40
    python scripts/verify_android_100_accuracy.py adb.log --prefix Q500 --total 500
"""
from __future__ import annotations
import argparse
import math
import re
import sys
from pathlib import Path

def gate(log: str, expected_total: int=100, prefix: str="Q100",
         max_p95_ms: int | None=None) -> dict:
    if not re.fullmatch(r"Q(?:40|100|500)",prefix):
        raise AssertionError(f"Unsupported strict test prefix {prefix!r}")
    start_re=re.compile(rf"{prefix}_BEGIN\s+run_id=(\d+)\s+total=(\d+)")
    begins=list(start_re.finditer(log))
    if not begins:
        raise AssertionError("Missing QA run start marker; do not mix legacy logs")
    begin=begins[-1]
    run_id=begin.group(1)
    if int(begin.group(2))!=expected_total:
        raise AssertionError("Run start has the wrong question count")
    segment=log[begin.end():]
    finish_re=re.compile(rf"{prefix}_COMPLETE\s+run_id={re.escape(run_id)}\s+[^\n]*")
    finishes=list(finish_re.finditer(segment))
    if len(finishes)!=1:
        raise AssertionError("Must have exactly one matching run completion")
    text=segment[:finishes[0].start()]
    summary=finishes[0].group()
    tail=segment[finishes[0].end():]
    if re.search(rf"{prefix}_(?:CASE|COMPLETE)\b",tail):
        raise AssertionError("Unlinked QA cases or extra completions after final summary")
    case_re=re.compile(
        rf"{prefix}_CASE\s+run_id={re.escape(run_id)}\s+"
        rf"(\d+)/(\d+)\s+(PASS|FAIL)\b[^\n]*")
    cases=list(case_re.finditer(text))
    all_cases=re.findall(rf"{prefix}_CASE\b",text)
    if len(cases)!=len(all_cases):
        raise AssertionError("Found unlinked or malformed case lines")
    if len(cases)!=expected_total:
        raise AssertionError(f"Only {len(cases)}/{expected_total} case lines")
    rows={}
    for match in cases:
        number,total,status=int(match.group(1)),int(match.group(2)),match.group(3)
        if total!=expected_total or not (1<=number<=expected_total):
            raise AssertionError(f"Invalid case number {number}/{total}")
        if number in rows:
            raise AssertionError(f"Duplicate case {number}; results may be stale")
        line=match.group(0)
        def real(name: str) -> float:
            m=re.search(r"\b"+re.escape(name)+r"=(-?(?:\d+)(?:\.\d+)?)",line)
            if not m:raise AssertionError(f"Missing {name} for case {number}")
            value=float(m.group(1))
            if not math.isfinite(value):
                raise AssertionError(f"Nonfinite {name} for case {number}")
            return value
        length=int(real("spanLength"))
        coverage=real("goldCoverage")
        precision=real("highlightPrecision")
        if status!="PASS":
            raise AssertionError(f"Wrong top-1 source in case {number}")
        if re.search(r"\banchor=true\b",line) is None:
            raise AssertionError(f"Incorrect literal answer anchor in case {number}")
        if not 0<length<=360:
            raise AssertionError(f"Invalid/too broad highlight for case {number}")
        if coverage<.89999 or precision<.19999:
            raise AssertionError(f"Wrong original-offset answer span in case {number}")
        rows[number]={"span_length":length,"gold_coverage":coverage,
                      "highlight_precision":precision}
    if sorted(rows)!=list(range(1,expected_total+1)):
        raise AssertionError("Missing a case number inside the run")
    def metric(key: str) -> int:
        m=re.search(r"\b"+re.escape(key)+r"=(\d+)",summary)
        if not m:raise AssertionError(f"Missing final {key}")
        return int(m.group(1))
    for field in ("total","passed","highlighted","goldAnchors","verifiedAnchors"):
        if metric(field)!=expected_total:
            raise AssertionError(f"Final {field} does not equal {expected_total}")
    if max_p95_ms is not None and metric("p95_ms")>max_p95_ms:
        raise AssertionError("Observed p95 search time exceeds acceptance threshold")
    return {"prefix":prefix,"run_id":run_id,"total":expected_total,
            "passed":expected_total,"verified":expected_total,
            "median_ms":metric("median_ms"),"p95_ms":metric("p95_ms"),
            "max_ms":metric("max_ms"),"cases":rows}

def main() -> int:
    p=argparse.ArgumentParser()
    p.add_argument("log",type=Path)
    p.add_argument("--prefix",choices=["Q100","Q40","Q500"],default="Q100")
    p.add_argument("--total",type=int,default=None)
    p.add_argument("--max-p95-ms",type=int,default=None)
    args=p.parse_args()
    total=args.total if args.total is not None else (
        40 if args.prefix=="Q40" else 500 if args.prefix=="Q500" else 100
    )
    try:
        r=gate(args.log.read_text(encoding="utf-8",errors="replace"),
               total,args.prefix,args.max_p95_ms)
    except (AssertionError,OSError) as ex:
        print(f"{args.prefix}_STRICT_GATE_FAIL",str(ex),file=sys.stderr)
        return 1
    print(f"{args.prefix}_STRICT_GATE_PASS run_id={r['run_id']} "
          f"sources={total}/{total} exact_anchors={total}/{total} "
          f"median_ms={r['median_ms']} p95_ms={r['p95_ms']}")
    return 0

if __name__=="__main__":
    raise SystemExit(main())
