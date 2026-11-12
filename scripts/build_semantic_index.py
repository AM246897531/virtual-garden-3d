#!/usr/bin/env python3
"""Train and package a genuinely learned, compact Hebrew distributional Word2Vec + learned subword retrieval model.

No remote inference or runtime network access. Inputs: locally licensed Mishneh Torah
SQLite corpus.  Output: deterministic binary embeddings of corpus laws, a learned
Hebrew subword model and a vocabulary, read by SemanticIndex.kt on Android.

This is in-domain unsupervised retrieval, not an instruction-tuned model, and it
never generates a halakhic answer. The original text remains authoritative.
"""
import json
import math
import re
import sqlite3
import struct
from collections import Counter
from pathlib import Path

import numpy as np
from gensim.models import Word2Vec
from gensim.models.fasttext import ft_ngram_hashes

ROOT = Path(__file__).resolve().parents[1]
DB = ROOT / 'app/src/main/assets/rambam.sqlite'
OUT = ROOT / 'app/src/main/assets/hebrew_semantic.bin'
REPORT = ROOT / 'artifacts/semantic_report.json'
DIM = 80
BUCKETS = 24000
MIN_N = 3
MAX_N = 5
STOP = {'אני','מה','הוא','היא','הם','הן','את','של','על','עם','או','כי','לא',
        'אין','יש','אם','אף','כל','גם','לו','לה','בו','זה','זו','כך','כדי',
        'מן','אשר','דבר','אחר','אחד','שני','שלא','יהיה','הרי','אבל','ואם',
        'ואין','ולא','לפי','בין','כגון','אלא','עד','היה','היו','האם','צריך',
        'אדם','כיצד','במה','כמו','אינו','אינה','כלום','בדבר','בזה','אותו','שיש',
        'אותה','יש','דברימ','שניהמ','עשה','הלכה'}


def tokenized(norm):
    return [w for w in norm.split() if len(w) >= 2]


def normalized_int8(arr):
    """Per-row unit normalized vectors represented as signed int8."""
    arr = np.asarray(arr, dtype=np.float32)
    length = np.linalg.norm(arr, axis=-1, keepdims=True)
    arr = np.divide(arr, np.maximum(length, 1e-7))
    return np.rint(arr * 127).clip(-127, 127).astype('int8')


def main():
    with sqlite3.connect(DB) as con:
        data = con.execute('SELECT id, norm, section FROM laws ORDER BY id').fetchall()
    if len(data) < 15000:
        raise ValueError('Not enough source records to train semantic model')
    sentences = [tokenized(norm) for _, norm, _ in data]
    counts = Counter(w for words in sentences for w in set(words))
    print('SEMANTIC_TRAINING',len(data),'docs',len(counts),'terms',flush=True)
    # True learned Hebrew skip-gram embeddings trained in domain. Subword
    # buckets are distilled from these learned vectors for unseen queries.
    # Multi-worker asynchronous updates make the binary model vary between
    # otherwise identical CI runs. One worker + fixed seed + PYTHONHASHSEED=0
    # produces reproducible source rankings and answer-span benchmarks.
    # Never trade evaluation reproducibility for training throughput.
    model = Word2Vec(vector_size=DIM, window=6, min_count=2, workers=1,
                     sg=1, negative=5, epochs=9, seed=1729, sample=0.0003)
    model.build_vocab(corpus_iterable=sentences)
    model.train(corpus_iterable=sentences,
                total_examples=len(sentences), epochs=model.epochs)

    # In-domain word embeddings; out-of-vocabulary word meanings are approximated
    # from a learned aggregate of same-language subword neighbors.
    vocab = sorted(model.wv.key_to_index)
    weights = {word: min(4.0, 1.0 + math.log((len(data)+1)/(1+counts[word])))
               for word in vocab}
    term_vectors = normalized_int8([model.wv[word] for word in vocab])
    doc_ids = []
    doc_vectors = []
    for (doc_id, norm, section), words in zip(data, sentences):
        scores = []
        vectors = []
        for word in words:
            if word in model.wv.key_to_index and word not in STOP:
                vectors.append(model.wv[word])
                scores.append(weights[word])
        # Keep topic signal from the work/halakhic heading.
        secwords = tokenized(re.sub(r'[^\w\u0590-\u05ff]+',' ',section))
        for word in secwords:
            if word in model.wv.key_to_index and word not in STOP:
                vectors.append(model.wv[word])
                scores.append(weights[word] * 1.3)
        doc_ids.append(doc_id)
        if vectors:
            doc_vectors.append(np.average(np.stack(vectors), axis=0, weights=scores))
        else:
            doc_vectors.append(np.zeros(DIM, dtype=np.float32))
    doc_vectors = normalized_int8(np.stack(doc_vectors))

    # Distill Hebrew morphology into subword buckets by averaging the learned
    # word embedding of all training terms that share each character n-gram.
    # High-frequency generic substrings do not dominate OOV query vectors.
    ngrams = np.zeros((BUCKETS, DIM),dtype=np.float32)
    ngram_counts = np.zeros(BUCKETS,dtype=np.int32)
    for word in vocab:
        vec = np.asarray(model.wv[word],dtype=np.float32)
        for bucket in set(ft_ngram_hashes(word, MIN_N, MAX_N, BUCKETS)):
            ngrams[bucket] += vec
            ngram_counts[bucket] += 1
    nonempty = ngram_counts > 0
    ngrams[nonempty] /= ngram_counts[nonempty,None]
    scale = float(np.max(np.abs(ngrams)) / 127.0)
    ngram_bytes = np.rint(ngrams / scale).clip(-127,127).astype('int8')
    OUT.parent.mkdir(parents=True, exist_ok=True)
    with OUT.open('wb') as out:
        out.write(b'RAMSEM01')
        out.write(struct.pack('<IIIIIf', DIM, len(vocab), len(data), BUCKETS,
                              (MIN_N << 16) | MAX_N, scale))
        for word, vector in zip(vocab, term_vectors):
            encoded=word.encode('utf8')
            if len(encoded) > 65535: raise ValueError('Oversized token')
            out.write(struct.pack('<Hf',len(encoded),weights[word]))
            out.write(encoded)
            out.write(vector.tobytes())
        out.write(ngram_bytes.tobytes())
        for doc_id, vector in zip(doc_ids, doc_vectors):
            out.write(struct.pack('<i', doc_id))
            out.write(vector.tobytes())
    check = {
        'model': 'Hebrew Word2Vec skip-gram with learned morphological subword embeddings',
        'training_corpus': 'Mishneh Torah, exact licensed local halakhic text only',
        'dimensions':DIM,'ngram_buckets':BUCKETS,'vocab_size':len(vocab),
        'indexed_halakhot':len(data),'file_size_bytes':OUT.stat().st_size,
        'offline_inference':True,
        'training_workers':1,'training_seed':1729,
        'deterministic_when_pythonhashseed_fixed':True,
        'limitations':'In-domain learned retrieval, not a pretrained large language model or halakhic ruling engine',
    }
    REPORT.parent.mkdir(parents=True,exist_ok=True)
    REPORT.write_text(json.dumps(check,ensure_ascii=False,indent=2),encoding='utf8')
    print('SEMANTIC_READY',json.dumps(check,ensure_ascii=False),flush=True)

if __name__ == '__main__':
    main()