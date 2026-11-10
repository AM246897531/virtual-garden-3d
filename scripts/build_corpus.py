#!/usr/bin/env python3
"""Reproducible Mishneh Torah corpus builder with explicit per-chapter source provenance.
We prefer public-domain Torat Emet, then use a CC-BY-SA Wikisource chapter when
necessary. A chapter is never mixed silently; source selection is recorded.
"""
from __future__ import annotations
import hashlib, html, json, re, sqlite3, time, unicodedata
from pathlib import Path
from collections import Counter, defaultdict
import requests

ROOT = Path(__file__).resolve().parents[1]
ARTIFACTS = ROOT / "artifacts"
RAW = ARTIFACTS / "raw-corpus"
ASSETS = ROOT / "app/src/main/assets"
INDEX_URL = "https://raw.githubusercontent.com/Sefaria/Sefaria-Export/master/books.json"
PREFERRED = ["Torat Emet 363", "Torat Emet 370", "Torat Emet 363 - censorship omissions reinserted  ", "Wikisource Mishneh Torah"]
EXCLUSIONS = {
    "Mishneh Torah, Positive Mitzvot", "Mishneh Torah, Negative Mitzvot",
    "Mishneh Torah, Overview of Mishneh Torah Contents",
    "Mishneh Torah, The Order of Prayer",
    "Mishneh Torah, Transmission of the Oral Law",
}
BOOKS = {
    "mada": "ספר המדע", "madda": "ספר המדע", "ahavah": "ספר אהבה",
    "ahava": "ספר אהבה", "zemanim": "ספר זמנים", "nashim": "ספר נשים",
    "kedushah": "ספר קדושה", "haflaah": "ספר הפלאה", "zeraim": "ספר זרעים",
    "avodah": "ספר עבודה", "korbanot": "ספר קרבנות", "taharah": "ספר טהרה",
    "nezikim": "ספר נזיקין", "kinyan": "ספר קניין", "mishpatim": "ספר משפטים",
    "shoftim": "ספר שופטים",
}
MARKS = re.compile(r"[\u0591-\u05c7]")
HTML_TAGS = re.compile(r"<[^>]+>")
SYMBOLS = re.compile(r"[^\w\u0590-\u05ff]+", re.UNICODE)

def normalize(s: str) -> str:
    s = unicodedata.normalize("NFKC", s)
    s = MARKS.sub("", s)
    s = re.sub(r"[\u05be\u05c0\u05c3\u05f3\u05f4]", " ", s)
    s = s.replace("ך", "כ").replace("ם", "מ").replace("ן", "נ")
    s = s.replace("ף", "פ").replace("ץ", "צ")
    return " ".join(SYMBOLS.sub(" ", s.lower()).split())

def cleaned_text(s: str) -> str:
    return html.unescape(HTML_TAGS.sub("", s)).strip()

def lookup_book(categories: list[str]) -> str | None:
    for cat in categories:
        candidate = cat.lower().replace(" ", "").replace("-", "").replace("'", "")
        if candidate.startswith("sefer"):
            return BOOKS.get(candidate[5:])
    return None

def download(session: requests.Session, url: str) -> tuple[dict, bytes]:
    for attempt in range(3):
        try:
            r = session.get(url, timeout=100)
            r.raise_for_status()
            return r.json(), r.content
        except Exception:
            if attempt == 2: raise
            time.sleep(2 ** attempt)
    raise RuntimeError("Unreachable")

def is_complete_chapter(ch) -> bool:
    return isinstance(ch, list) and len(ch) > 0 and all(
        isinstance(v, str) and cleaned_text(v) for v in ch
    )

def main():
    for p in (ARTIFACTS, RAW, ASSETS): p.mkdir(parents=True, exist_ok=True)
    s = requests.Session()
    s.headers["User-Agent"] = "MishnehTorahOffline/1.0 provenance-preserving-build"
    book_index, index_bytes = download(s, INDEX_URL)
    primary = [x for x in book_index.get("books", []) if
        x.get("title", "").startswith("Mishneh Torah, ") and
        x.get("title") not in EXCLUSIONS and
        x.get("language", "").lower() in ("hebrew", "he") and
        x.get("versionTitle") in PREFERRED and x.get("json_url")]
    by_title: dict[str, dict] = defaultdict(dict)
    for e in primary:
        by_title[e["title"]][e["versionTitle"]] = e
    print("FOUND_HALAKHIC_SECTIONS", len(by_title), flush=True)
    if len(by_title) != 83:
        raise RuntimeError("Expected 83 halakhic sections, got " + str(len(by_title)))
    manifest = []
    chapters_used = Counter()
    by_book = Counter()
    records = []
    seen = set()
    for title in sorted(by_title):
        sources = []
        for ver in PREFERRED:
            entry = by_title[title].get(ver)
            if not entry: continue
            data, raw = download(s, entry["json_url"])
            license_ = (data.get("license") or "").strip()
            if license_.lower() not in ("public domain", "pd", "cc0", "cc-by-sa", "cc by-sa"):
                print("SKIP_UNVERIFIED_LICENSE", title, ver, repr(license_), flush=True)
                continue
            if not isinstance(data.get("text"), list): continue
            slug = re.sub("[^a-zA-Z0-9_]", "_", title)[:110] + "__" + re.sub("[^a-zA-Z0-9_]", "_", ver) + ".json"
            (RAW / slug).write_bytes(raw)
            sources.append({
                "version": ver, "entry": entry, "data": data, "sha256": hashlib.sha256(raw).hexdigest(),
                "license": license_, "url": entry["json_url"],
            })
        if not sources: raise RuntimeError("No legal editions for " + title)
        first = sources[0]
        book = lookup_book(first["data"].get("categories") or first["entry"].get("categories") or [])
        if not book: raise RuntimeError("Unknown sefer for " + title)
        section = (first["data"].get("heTitle") or first["entry"].get("heTitle")
                   or title.split(", ", 1)[-1])
        maximum = max(len(x["data"]["text"]) for x in sources)
        for cidx in range(maximum):
            chosen = next((src for src in sources if cidx < len(src["data"]["text"]) and
                           is_complete_chapter(src["data"]["text"][cidx])), None)
            if not chosen:
                raise RuntimeError("No complete lawful chapter for " + title + " chapter " + str(cidx+1))
            laws = chosen["data"]["text"][cidx]
            for law_index, law in enumerate(laws, 1):
                key = (title, cidx+1, law_index)
                if key in seen: raise RuntimeError("Duplicate " + str(key))
                seen.add(key)
                body = cleaned_text(law)
                records.append((book, section, cidx+1, law_index,
                                body, normalize(body), title, chosen["version"]))
                by_book[book] += 1
            chapters_used[chosen["version"]] += 1
        manifest.append({"title": title, "heTitle": section, "book": book,
            "chapters": maximum, "sources": [{k: v for k,v in src.items()
              if k not in ("data", "entry")} for src in sources]})
        print("SECTION_OK", title, maximum, "chapters", flush=True)
    if len(by_book) != 14 or len(records) < 10000:
        raise RuntimeError("Incomplete corpus: " + json.dumps(by_book, ensure_ascii=False))
    target = ASSETS / "rambam.sqlite"
    if target.exists(): target.unlink()
    db = sqlite3.connect(target)
    try:
        db.executescript("""
        CREATE TABLE laws (
          id INTEGER PRIMARY KEY, book TEXT NOT NULL, section TEXT NOT NULL,
          chapter INTEGER NOT NULL, halakhah INTEGER NOT NULL,
          body TEXT NOT NULL, norm TEXT NOT NULL, english_section TEXT NOT NULL,
          edition TEXT NOT NULL
        );
        CREATE INDEX laws_browse ON laws(book,section,chapter,halakhah);
        CREATE UNIQUE INDEX laws_identity ON laws(english_section,chapter,halakhah);
        CREATE VIRTUAL TABLE laws_fts USING fts4(norm, tokenize=unicode61);
        """)
        for item in records:
            cur = db.execute(
                "INSERT INTO laws(book,section,chapter,halakhah,body,norm,english_section,edition) VALUES(?,?,?,?,?,?,?,?)", item)
            db.execute("INSERT INTO laws_fts(rowid,norm) VALUES (?,?)", (cur.lastrowid, item[5]))
        db.commit()
        assert db.execute("PRAGMA integrity_check").fetchone()[0] == "ok"
        assert db.execute("SELECT count(*) FROM laws").fetchone()[0] == len(records)
    finally: db.close()
    report = {"total_books": len(by_book), "total_sections": len(by_title),
        "halakhot": len(records), "books": dict(by_book), "chapters_by_edition": dict(chapters_used),
        "sqlite_sha256": hashlib.sha256(target.read_bytes()).hexdigest(),
        "source_note": "Chapter-by-chapter selection; per-law edition column preserved"}
    (ARTIFACTS / "corpus_report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf8")
    (ARTIFACTS / "SOURCE_MANIFEST.json").write_text(json.dumps(
        {"index": INDEX_URL, "index_sha256": hashlib.sha256(index_bytes).hexdigest(),
         "sections": manifest, "license_policy": "Public Domain or CC-BY-SA only"},
        ensure_ascii=False, indent=2), encoding="utf8")
    print("SUCCESS", len(records), "halakhot", len(by_book), "books", dict(chapters_used), flush=True)

if __name__ == "__main__": main()
