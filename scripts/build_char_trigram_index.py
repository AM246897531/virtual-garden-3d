#!/usr/bin/env python3
"""Build a compact offline char-trigram TF-IDF inverted index for Android.
This is a source-only Python stage; running it does NOT compile an APK.
Format RCHAR301: magic(8), docCount(u32), termCount(u32), postings(u32),
doc IDs(i32*count), then term records [len(u8), UTF8 gram, start(u32),
count(u32), idf(f32)], then (docIndex u32, quantized L2 weight u16) postings.
"""
from __future__ import annotations
import json
import sqlite3
import struct
from pathlib import Path

import numpy as np
from sklearn.feature_extraction.text import TfidfVectorizer

ROOT = Path(__file__).resolve().parents[1]
DB = ROOT / "app/src/main/assets/rambam.sqlite"
OUTPUT = ROOT / "app/src/main/assets/rambam_char3.bin"
REPORT = ROOT / "artifacts/char3_report.json"

def main() -> None:
    assert DB.exists(), "Build the licensed corpus first"
    conn = sqlite3.connect(DB)
    rows = conn.execute("SELECT id,norm FROM laws ORDER BY id").fetchall()
    assert len(rows) >= 15000, f"Incomplete Mishneh Torah: {len(rows)} laws"
    doc_ids = np.asarray([row[0] for row in rows], dtype="<i4")
    vectorizer = TfidfVectorizer(analyzer="char", ngram_range=(3,3),
                                min_df=2, sublinear_tf=True, dtype=np.float32)
    encoded = vectorizer.fit_transform([row[1] for row in rows])
    grams = vectorizer.get_feature_names_out()
    postings = encoded.T.tocsr()
    assert postings.shape[1] == len(doc_ids)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with OUTPUT.open("wb") as stream:
        stream.write(b"RCHAR301")
        stream.write(struct.pack("<III", len(doc_ids), len(grams), int(postings.nnz)))
        stream.write(doc_ids.tobytes())
        for i, gram in enumerate(grams):
            raw = gram.encode("utf-8")
            assert 1 <= len(raw) <= 255
            stream.write(struct.pack("<B", len(raw)))
            stream.write(raw)
            stream.write(struct.pack("<IIf", int(postings.indptr[i]),
                                     int(postings.indptr[i+1]-postings.indptr[i]),
                                     float(vectorizer.idf_[i])))
        for i in range(len(grams)):
            a,b=postings.indptr[i:i+2]
            for j,weight in zip(postings.indices[a:b],postings.data[a:b]):
                quantized=max(0,min(65535,int(round(float(weight)*65535))))
                stream.write(struct.pack("<IH",int(j),quantized))
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    report={"format":"RCHAR301","laws":len(doc_ids),"ngrams":len(grams),
            "postings":int(postings.nnz),"bytes":OUTPUT.stat().st_size,
            "algorithm":"char trigram TF-IDF with sublinear term frequency, L2 normalized law vectors",
            "purpose":"high-recall candidate retrieval only; not a verified answer or highlight"}
    REPORT.write_text(json.dumps(report,ensure_ascii=False,indent=2)+"\n")
    print("OFFLINE_CHAR3_INDEX",report,flush=True)

if __name__=="__main__":
    main()
