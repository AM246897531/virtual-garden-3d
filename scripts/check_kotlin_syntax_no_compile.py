#!/usr/bin/env python3
"""Parse all modified Kotlin source files without Gradle, Kotlin compiler or APK.

This catches malformed syntax after code edits. It does not prove type
checking, Android integration, runtime correctness or benchmark accuracy.
"""
from __future__ import annotations
import json
from pathlib import Path
from tree_sitter import Language,Parser
import tree_sitter_kotlin

ROOT=Path(__file__).resolve().parents[1]
APP=ROOT/"app/src/main/java/app/mishnehtorah"
FILES=("SearchLanguage.kt","PreciseEvidenceSearch.kt","CorpusStore.kt",
       "SemanticIndex.kt","CharTrigramIndex.kt","MainActivity.kt")

def errors_in_node(node,raw):
    out=[]
    stack=[node]
    while stack:
        current=stack.pop()
        if current.type=="ERROR" or current.is_missing:
            row,col=current.start_point
            out.append({"line":row+1,"column":col+1,
                        "kind":current.type,"snippet":raw[current.start_byte:current.end_byte]
                        .decode("utf-8",errors="replace")[:150]})
        stack.extend(reversed(current.children))
    return out

def main():
    parser=Parser(Language(tree_sitter_kotlin.language()))
    report={}
    for name in FILES:
        file=APP/name
        raw=file.read_bytes()
        tree=parser.parse(raw)
        errors=errors_in_node(tree.root_node,raw) if tree.root_node.has_error else []
        report[name]={"syntax_errors":len(errors),"details":errors[:20]}
        print(f"KOTLIN_PARSE_ONLY {name}: syntax_errors={len(errors)}",flush=True)
    output=ROOT/"artifacts/kotlin_parse_no_compile.json"
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(json.dumps(report,indent=2,ensure_ascii=False)+"\n")
    failed=[name for name,item in report.items() if item["syntax_errors"]]
    if failed:
        for name in failed:print(json.dumps({name:report[name]},ensure_ascii=False))
        raise SystemExit(f"Kotlin source parse error in: {', '.join(failed)}")
    print("KOTLIN_PARSE_ONLY_PASS no_gradle=True no_apk=True")
if __name__=="__main__":main()
