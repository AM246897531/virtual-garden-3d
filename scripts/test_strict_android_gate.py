#!/usr/bin/env python3
"""Adversarial parser tests only. Mock logs are NOT app accuracy evidence."""
from verify_android_100_accuracy import gate

def simulated(total,prefix,run_id="123456"):
    lines=[f"{prefix}_BEGIN run_id={run_id} total={total}"]
    for i in range(1,total+1):
        cohort=("synthetic" if i>140 else "reviewed")
        lines.append(
            f"{prefix}_CASE run_id={run_id} {i}/{total} PASS "
            f"tier=3 top=Test expected=Test cohort={cohort} passage=true anchor=true "
            "goldCoverage=1.0 highlightPrecision=0.5 "
            "spanLength=42 elapsed_ms=120 excerpt=Source"
        )
    cohorts=(" reviewedTotal=140 reviewedTop1=140 reviewedHighlights=140 "
             "syntheticTotal=360 syntheticTop1=360 syntheticHighlights=360"
             if prefix=="Q500" else "")
    lines.append(f"{prefix}_COMPLETE run_id={run_id} "
                 f"passed={total} total={total} highlighted={total} "
                 f"verifiedAnchors={total} goldAnchors={total} "
                 "median_ms=120 p95_ms=300 max_ms=390"+cohorts)
    return "\n".join(lines)

def must_reject(log,total=100,prefix="Q100"):
    try:gate(log,total,prefix)
    except AssertionError:return
    raise AssertionError("Accepted malformed or inconsistent QA evidence")

def main():
    normal=simulated(100,"Q100")
    assert gate(normal)["passed"]==100
    assert gate(simulated(40,"Q40"),40,"Q40")["verified"]==40
    # This is a LOG PARSER test, not 500 actual Android searches.
    assert gate(simulated(500,"Q500"),500,"Q500")["verified"]==500
    must_reject(simulated(500,"Q500").replace(
        "Q500_CASE run_id=123456 250/500 PASS",
        "Q500_CASE run_id=123456 250/500 FAIL",1),500,"Q500")
    must_reject(simulated(500,"Q500").replace(
        "syntheticTotal=360","syntheticTotal=359",1),500,"Q500")
    must_reject(simulated(500,"Q500").replace(
        "250/500 PASS tier=3","250/500 PASS tier=3",1
    ).replace("cohort=synthetic","cohort=reviewed",1),500,"Q500")
    must_reject(normal.replace("1/100 PASS","1/100 FAIL",1))
    must_reject(normal.replace("goldCoverage=1.0","goldCoverage=0.80",1))
    must_reject(normal.replace("highlightPrecision=0.5","highlightPrecision=0.1",1))
    must_reject(normal.replace("spanLength=42","spanLength=999",1))
    must_reject(normal.replace("passed=100 total=100","passed=99 total=100"))
    must_reject(normal.replace("run_id=123456 5/100","run_id=987654 5/100"))
    lines=normal.splitlines()
    must_reject("\n".join(line for line in lines if " 40/100 " not in line))
    must_reject(normal+"\n"+lines[8])
    must_reject(normal.replace("Q100_BEGIN","Q100_OLD_BEGIN",1))
    must_reject(normal.replace("Q100_COMPLETE","Q100_BAD_COMPLETE",1))
    must_reject(normal,40,"Q40")
    assert gate(simulated(100,"Q100","0001")+"\n"+
                simulated(100,"Q100","0002"))["run_id"]=="0002"
    must_reject(simulated(100,"Q100")+"\n"+
                simulated(100,"Q100","987654").split("Q100_CASE",1)[0])
    try:gate(normal,100,"Q100",max_p95_ms=200)
    except AssertionError:pass
    else:raise AssertionError("Accepted search latency over specified cap")
    print("STRICT_QA_GATE_STATIC_TESTS_PASS examples=19 no_actual_device_test=True")

if __name__=="__main__":main()
