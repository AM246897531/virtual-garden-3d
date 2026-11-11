#!/usr/bin/env python3
"""STRICT, reproducible independent acceptance gate for Android Q100 logs.
Call only on REAL adb logcat output from the app. No APK build occurs here.
"""
import argparse, re, sys
from pathlib import Path

def gate(log: str, expected_total: int=100):
    case_rx=re.compile(r"Q100_CASE\s+(\d+)/(\d+)\s+(PASS|FAIL)\b[^\n]*")
    rows={}
    for m in case_rx.finditer(log):
        index,total,status=int(m.group(1)),int(m.group(2)),m.group(3)
        if total!=expected_total or not (1<=index<=expected_total):
            raise AssertionError(f"Unexpected case {index}/{total}")
        line=m.group(0)
        span_m=re.search(r"spanLength=(-?\d+)",line)
        if not span_m:raise AssertionError(f"No exact span length for case {index}")
        span=int(span_m.group(1))
        rows[index]={"source_pass":status=="PASS",
                     "gold_anchor_pass":bool(re.search(r"\banchor=true\b",line)),
                     "span_length":span}
    summary=re.findall(r"Q100_COMPLETE\s+[^\n]*",log)
    if not summary:raise AssertionError("No completed Q100 Android run; do not claim accuracy")
    tail=summary[-1]
    def metric(key):
        m=re.search(r"\b"+re.escape(key)+r"=(\d+)",tail)
        if not m:raise AssertionError("Missing "+key)
        return int(m.group(1))
    assert len(rows)==expected_total, f"Only {len(rows)}/{expected_total} distinct cases"
    assert metric("total")==expected_total, "Incomplete test run"
    assert metric("passed")==expected_total, "Top-1 references failed"
    assert metric("goldAnchors")==expected_total, "Some answer anchors missing"
    assert metric("verifiedAnchors")==expected_total, "Some anchor highlights failed"
    for i in range(1,expected_total+1):
        case=rows[i]
        assert case["source_pass"] and case["gold_anchor_pass"], f"Failed {i}"
        assert 0<case["span_length"]<=360, f"Too broad or invalid highlight for {i}"
    return rows

def main():
    p=argparse.ArgumentParser()
    p.add_argument("log",type=Path)
    args=p.parse_args()
    try:
        cases=gate(args.log.read_text(encoding="utf-8",errors="replace"))
    except (AssertionError,OSError) as ex:
        print("Q100_STRICT_GATE_FAIL",str(ex),file=sys.stderr)
        return 1
    print("Q100_STRICT_GATE_PASS 100/100 independent Android top-1 sources, "
          "100/100 anchors and all exact spans <= 360 characters")
    return 0

if __name__=="__main__":raise SystemExit(main())
