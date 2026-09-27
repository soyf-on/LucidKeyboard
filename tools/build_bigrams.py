#!/usr/bin/env python3
"""Builds next-word (bigram) counts from Tatoeba sentences.

Reproduce:
    curl -LO https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2   (and nld)
    python tools/build_bigrams.py en tools/data/eng_sentences.tsv.bz2 3
    python tools/build_bigrams.py nl tools/data/nld_sentences.tsv.bz2 2

Output: lexicon/<lang>_bigrams.tsv   prev <TAB> next <TAB> count   ("<s>" = sentence start)
Only pairs whose words are both in lexicon/<lang>_words.tsv are kept.
Tatoeba sentences are CC BY 2.0 FR (https://tatoeba.org); the counts are a derived work.
"""
import bz2, collections, os, re, sys, unicodedata

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOKEN = re.compile(r"[a-zà-öø-ÿ]+(?:['’][a-zà-öø-ÿ]+)*")
SPLIT = re.compile(r"(?<=[.!?])\s+")


def main(lang, path, min_count):
    vocab = set()
    for line in open(os.path.join(ROOT, "lexicon", f"{lang}_words.tsv"), encoding="utf-8"):
        vocab.add(line.split("\t", 1)[0].lower())
    counts = collections.Counter()
    for line in bz2.open(path, "rt", encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 3:
            continue
        for sent in SPLIT.split(parts[2]):
            toks = [t.replace("’", "'") for t in TOKEN.findall(sent.lower())]
            prev = "<s>"
            for t in toks:
                if t not in vocab:
                    prev = None  # unknown word breaks the chain
                    continue
                if prev is not None:
                    counts[(prev, t)] += 1
                prev = t
    kept = [(p, n, c) for (p, n), c in counts.items() if c >= min_count]
    kept.sort(key=lambda r: (r[0], -r[2]))
    with open(os.path.join(ROOT, "lexicon", f"{lang}_bigrams.tsv"), "w", encoding="utf-8") as fh:
        for p, n, c in kept:
            fh.write(f"{p}\t{n}\t{c}\n")
    print(f"{lang}: {len(counts)} bigram types, kept {len(kept)} (count >= {min_count})")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2], int(sys.argv[3]))
