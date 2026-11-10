#!/usr/bin/env python3
"""Acquire legally reusable complete Hebrew Mishneh Torah and build the offline SQLite asset.
This script DOES NOT accept merged texts or silently fill gaps.
Run: python scripts/build_corpus.py
"""
from __future__ import annotations
import hashlib, html, json, os, re, sqlite3, sys, time, unicodedata
from collections import Counter, defaultdict
from pathlib import Path
from urllib.parse import quote
import requests

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/assets"
ARTIFACTS = ROOT / "artifacts"
RAW = ARTIFACTS / "raw-corpus"
INDEX_URL = "https://raw.githubusercontent.com/Sefaria/Sefaria-Export/master/books.json"
VERSION = "Torat Emet 363"
BOOKS = {
    "Mada": "ספר המדע", "Ahavah": "ספר אהבה", "Zemanim": "ספר זמנים",
    "Nashim": "ספר נשים", "Kedushah": "ספר קדושה", "Haflaah": "ספר הפלאה",
    "Hafla'ah": "ספר הפלאה", "Zeraim": "ספר זרעים", "Avodah": "ספר עבודה",
    "Korbanot": "ספר קרבנות", "Taharah": "ספר טהרה", "Nezikim": "ספר נזיקין",
    "Kinyan": "ספר קניין", "Mishpatim": "ספר משפטים", "Shoftim": "ספר שופטים",
}
HEBREW_MARKS = re.compile(r"[\u0591-\u05c7]")
PUNCTUATION = re.compile(r"[^\w\u0590-\u05ff]+", re.UNICODE)
TAGS = re.compile(r"<[^>]+>")

def normalize(s: str) -> str:
    s = unicodedata.normalize("NFKC", s)
    s = HEBREW_MARKS.sub("", s)
    s = s.replace("ך", "כ").replace("ם", "מ").replace("ן", "נ").replace("ף", "פ").replace("ץ", "צ")
    s = PUNCTUATION.sub(" ", s)
    return " ".join(s.lower().split())

def cleaned_text(s: str) -> str:
    # The original exported content is archived untouched. HTML markup is not displayed as text.
    return html.unescape(TAGS.sub("", s)).strip()

def lookup_book(categories: list[str]) -> str | None:
    for cat in categories:
        c = cat.lower().replace(" ", "").replace("-", "").replace("_", "")
        if c.startswith("sefer"):
            key = c[5:]
            for src, he in BOOKS.items():
                if src.lower().replace("'", "") == key.replace("'", ""):
                    return he
    return None

def fetch_json(session, url: str) -> tuple[dict, bytes]:
    for attempt in range(4):
        try:
            response = session.get(url, timeout=100)
            response.raise_for_status()
            b = response.content
            return json.loads(b), b
        except Exception as e:
            if attempt == 3:
                raise RuntimeError("Download failed for " + url + ": " + str(e)) from e
            time.sleep(2 ** attempt)
    raise AssertionError

def main() -> None:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    ARTIFACTS.mkdir(parents=True, exist_ok=True)
    RAW.mkdir(parents=True, exist_ok=True)
    session = requests.Session()
    session.headers["User-Agent"] = "MishnehTorah-Offline-corpus-builder/1.0"
    index, index_bytes = fetch_json(session, INDEX_URL)
    candidates = [x for x in index.get("books", []) if
        x.get("versionTitle") == VERSION and
        x.get("language", "").lower() in ("hebrew", "he") and
        "Mishneh Torah" in x.get("categories", []) and x.get("json_url")]
    # Fail closed: do not use a merged file or an unknown-license version to mask missing text.
    if len(candidates) < 80:
        raise RuntimeError("Incomplete candidate list: found " + str(len(candidates)) +
                           " sections in Sefaria index, expected at least 80.")
    candidates.sort(key=lambda x: x["title"])
    corpus = []
    sources = []
    duplicates = []
    seen_keys = set()
    by_book = Counter()
    for entry in candidates:
        url = entry["json_url"]
        data, raw = fetch_json(session, url)
        license_text = str(data.get("license") or "").strip()
        if license_text.lower() not in ("public domain", "pd", "cc0"):
            raise RuntimeError("Refusing to package unverified license for " +
                               entry["title"] + ": " + repr(license_text))
        categories = data.get("categories") or entry.get("categories") or []
        book = lookup_book(categories)
        if not book:
            raise RuntimeError("Unknown Mishneh Torah sefer category: " + repr(categories))
        section = data.get("heTitle") or entry.get("heTitle")
        if not section:
            raise RuntimeError("Missing Hebrew title: " + entry["title"])
        chapters = data.get("text")
        if not isinstance(chapters, list) or not chapters:
            raise RuntimeError("Missing chapters in " + entry["title"])
        file_name = re.sub(r"[^A-Za-z0-9_-]", "_", entry["title"]) + ".json"
        (RAW / file_name).write_bytes(raw)
        downloaded = 0
        for ch_i, chapter in enumerate(chapters, 1):
            if not isinstance(chapter, list) or not chapter:
                raise RuntimeError("Empty/non-list chapter " + entry["title"] + ":" + str(ch_i))
            for law_i, value in enumerate(chapter, 1):
                if not isinstance(value, str) or not cleaned_text(value):
                    raise RuntimeError("Empty/non-text law " + entry["title"] +
                                       ":" + str(ch_i) + ":" + str(law_i))
                key = (entry["title"], ch_i, law_i)
                if key in seen_keys:
                    duplicates.append(str(key))
                    continue
                seen_keys.add(key)
                body = cleaned_text(value)
                corpus.append((book, section, ch_i, law_i, body, normalize(body), entry["title"]))
                downloaded += 1
        by_book[book] += downloaded
        sources.append({"title": entry["title"], "heTitle": section, "book": book,
            "url": url, "versionTitle": VERSION, "license": license_text,
            "sha256": hashlib.sha256(raw).hexdigest(), "chapters": len(chapters),
            "halakhot": downloaded, "versionSource": data.get("versionSource", "")})
        print("OK", book, section, downloaded, flush=True)

    expected_book_names = set(BOOKS.values())
    if len(by_book) != 14 or not expected_book_names.issubset(set(by_book)):
        raise RuntimeError("Not all 14 books found: " + json.dumps(by_book, ensure_ascii=False))
    if duplicates or len(corpus) < 10000:
        raise RuntimeError("Corpus validation failed: duplicates=" + str(len(duplicates)) +
                           " total=" + str(len(corpus)))
    dbpath = OUTPUT / "rambam.sqlite"
    if dbpath.exists():
        dbpath.unlink()
    con = sqlite3.connect(dbpath)
    try:
        con.executescript("""
        CREATE TABLE laws(
          id INTEGER PRIMARY KEY, book TEXT NOT NULL, section TEXT NOT NULL,
          chapter INTEGER NOT NULL, halakhah INTEGER NOT NULL,
          body TEXT NOT NULL, norm TEXT NOT NULL, english_section TEXT NOT NULL);
        CREATE INDEX ix_laws_book ON laws(book,section,chapter,halakhah);
        CREATE UNIQUE INDEX ux_law_ref ON laws(english_section,chapter,halakhah);
        CREATE VIRTUAL TABLE laws_fts USING fts4(norm, tokenize=unicode61);
        """)
        for record in corpus:
            cur = con.execute("INSERT INTO laws(book,section,chapter,halakhah,body,norm,english_section) VALUES(?,?,?,?,?,?,?)", record)
            con.execute("INSERT INTO laws_fts(rowid,norm) VALUES (?,?)", (cur.lastrowid, record[5]))
        con.commit()
        integrity = con.execute("PRAGMA integrity_check").fetchone()[0]
        if integrity != "ok":
            raise RuntimeError("SQLite integrity failure: " + integrity)
        count = con.execute("SELECT COUNT(*) FROM laws").fetchone()[0]
        if count != len(corpus):
            raise RuntimeError("SQLite count mismatch")
    finally:
        con.close()
    report = {"status": "complete", "total_sections": len(sources), "total_halakhot": len(corpus),
        "books": dict(by_book), "duplicates": duplicates, "sqlite_sha256":
        hashlib.sha256(dbpath.read_bytes()).hexdigest()}
    (ARTIFACTS / "corpus_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    (ARTIFACTS / "SOURCE_MANIFEST.json").write_text(json.dumps({"index_url": INDEX_URL,
        "index_sha256": hashlib.sha256(index_bytes).hexdigest(), "sources": sources},
        ensure_ascii=False, indent=2), encoding="utf-8")
    print("CORPUS_COMPLETE", report["total_sections"], report["total_halakhot"], dbpath.stat().st_size)

if __name__ == "__main__":
    main()
