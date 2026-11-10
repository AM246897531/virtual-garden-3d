#!/usr/bin/env python3
import unittest
from build_corpus import normalize, lookup_book
class CorpusTests(unittest.TestCase):
    def test_niqqud(self):
        self.assertEqual(normalize("בְּרָכוֹת"), normalize("ברכות"))
    def test_final_letters(self):
        self.assertEqual(normalize("מלך"), normalize("מלכ"))
    def test_punctuation(self):
        self.assertEqual(normalize('ר״ח'), normalize("ר'ח"))
    def test_book(self):
        self.assertEqual(lookup_book(["Halakhah", "Mishneh Torah", "Sefer Mada"]), "ספר המדע")
if __name__ == "__main__":
    unittest.main()
