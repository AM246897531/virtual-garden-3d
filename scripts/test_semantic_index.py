#!/usr/bin/env python3
"""Gate the APK on real learned Hebrew assets and source retrieval smoke checks."""
import math
import sqlite3
import struct
import unittest
from pathlib import Path
import numpy as np
from build_corpus import normalize

ROOT = Path(__file__).resolve().parents[1]
BIN = ROOT / 'app/src/main/assets/hebrew_semantic.bin'
DB = ROOT / 'app/src/main/assets/rambam.sqlite'


def decode_model():
    blob = BIN.read_bytes()
    assert blob[:8] == b'RAMSEM01', 'No trained embeddings in APK'
    dim, vocab_size, doc_count, buckets, ngrams, scale = struct.unpack_from('<IIIIIf',blob,8)
    assert 64 <= dim <= 256 and vocab_size > 10000
    assert doc_count >= 15000 and buckets >= 1000 and scale > 0
    offset = 32
    terms = {}
    for _ in range(vocab_size):
        length, weight = struct.unpack_from('<Hf',blob,offset)
        offset += 6
        word = blob[offset:offset+length].decode('utf8')
        offset += length
        v = np.frombuffer(blob,dtype=np.int8,count=dim,offset=offset).astype('float32')/127
        offset += dim
        terms[word] = (weight,v)
    buckets_matrix = np.frombuffer(blob,dtype=np.int8,count=buckets*dim,offset=offset)
    assert buckets_matrix.size == buckets*dim
    offset += buckets*dim
    docs = {}
    for _ in range(doc_count):
        law_id, = struct.unpack_from('<i',blob,offset)
        offset += 4
        docs[law_id]=np.frombuffer(blob,dtype=np.int8,count=dim,offset=offset).astype('float32')/127
        offset += dim
    assert offset == len(blob)
    assert len(docs)==doc_count
    return terms,docs

class SemanticTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.terms,cls.docs = decode_model()
        cls.db = sqlite3.connect(DB)

    def test_model_bundled_with_all_actual_halakhot(self):
        self.assertEqual(len(self.docs), self.db.execute('SELECT count(*) FROM laws').fetchone()[0])
        self.assertGreater(len(self.terms),10000)
        self.assertLess(BIN.stat().st_size,12*1024*1024)

    def test_natural_hebrew_search_vectors_nonzero(self):
        for q in ('שכחתי ברכת המזון יעלה ויבוא','מה עושים בשבת','הלכות תפילין','מצוות צדקה'):
            usable = [self.terms[w][1] for w in normalize(q).split() if w in self.terms]
            self.assertTrue(usable, q)
            self.assertGreater(float(np.linalg.norm(np.mean(usable,axis=0))),0.01)

    def test_thematic_semantic_retrieval(self):
        # A word-level semantic index should recover target Rambam domains from
        # plain Hebrew, without consulting an LLM or making an online request.
        for q,needle in [
            ('תפילין', 'תפילין'),
            ('ציצית', 'ציצית'),
            ('שופר', 'שופר'),
            ('שכחתי ברכת המזון', 'ברכות'),
        ]:
            words=[w for w in normalize(q).split() if w in self.terms]
            qvec=np.mean([self.terms[w][1]*self.terms[w][0] for w in words],axis=0)
            qvec/=np.linalg.norm(qvec)
            ids=list(self.docs)
            ds=np.stack([self.docs[i] for i in ids])
            top=np.argsort(-(ds@qvec))[:150]
            top_ids=[ids[i] for i in top]
            params=','.join('?' for _ in top_ids)
            rows=self.db.execute(f'SELECT section FROM laws WHERE id IN ({params})',top_ids).fetchall()
            self.assertTrue(any(needle in r[0] for r in rows), q)

if __name__ == '__main__':
    unittest.main()