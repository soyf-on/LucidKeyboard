#!/usr/bin/env python3
"""Builds the bundled word lists from wordfreq + a spell-checked list per language.

Reproduce:
    uv venv .venv && . .venv/bin/activate && uv pip install wordfreq==3.1.1
    unmunch tools/data/en_US.dic tools/data/en_US.aff > /tmp/scowl.txt
    python tools/build_lexicon.py en /tmp/scowl.txt
    python tools/build_lexicon.py nl tools/data/opentaal-wordlist.txt

Outputs (committed):
    lexicon/<lang>_words.tsv     word <TAB> zipf*100 <TAB> V|F
        V = spell-checked (in SCOWL), F = frequent informal word not in SCOWL
    lexicon/en_replacements.tsv  from <TAB> to <TAB> gentle|balanced|strong
    lexicon/emoji.tsv            group <TAB> emoji

Licences: wordfreq data CC-BY-SA 4.0 (Robyn Speer), SCOWL (Kevin Atkinson,
permissive; see third_party/SCOWL-LICENSE.txt), emoji-test.txt Unicode License v3.
"""
import os
import re
import sys

import wordfreq

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "lexicon")
# Latin letters incl. the accented ones used in Dutch/English loanwords (ë, é, ï, ...).
L = "a-zà-öø-ÿ"
WORD_RE = re.compile(rf"^[{L}]+('[{L}]+)?$")
CONTRACTION_RE = re.compile(r"(n't|'m|'re|'ve|'ll|'d)$|^(that|what|he|she|there|here|who|where|how|everyone|someone|nobody)'s$")

MIN_ZIPF_VALID = 1.0      # spell-checked words: keep unless vanishingly rare
MIN_ZIPF_INFORMAL = 2.5   # slang / abbreviations not in SCOWL need real usage


LANG = "en"


def zipf(w):
    return wordfreq.zipf_frequency(w, LANG)


def load_scowl(path):
    cased = {}
    for line in open(path, encoding="utf-8", errors="ignore"):
        w = line.strip()
        if not w or not WORD_RE.match(w.lower()):
            continue
        cased.setdefault(w.lower(), set()).add(w)
    return cased


def best_case(lower, forms):
    if lower in forms:
        return lower
    return sorted(forms)[0]


def transpositions(w):
    for i in range(len(w) - 1):
        yield w[:i] + w[i + 1] + w[i] + w[i + 2:]


def substitutions(w):
    letters = "abcdefghijklmnopqrstuvwxyz"
    for i in range(len(w)):
        for c in letters:
            yield w[:i] + c + w[i + 1:]


def looks_like_typo(w, f, scowl):
    """Classic misspelling shapes only: swapped letters, or one wrong letter in a
    long word, where the correct spelling is >=20x more common. Dropped-g forms
    ("lookin") and brand names stay because they are deliberate."""
    if len(w) >= 3:
        for v in transpositions(w):
            if v != w and v in scowl and zipf(v) - f > 1.3:  # 10^1.3 ~= 20x
                return v
    if len(w) >= 7:
        for v in substitutions(w):
            if v != w and v in scowl and zipf(v) - f > 1.3:
                return v
    return None


def contraction_of(w, known):
    for i in range(1, len(w)):
        v = w[:i] + "'" + w[i:]
        if v in known:
            return v
    return None


def main(lang, scowl_path):
    global LANG
    LANG = lang
    scowl = load_scowl(scowl_path)
    candidates = [w for w in wordfreq.top_n_list(lang, 400000) if WORD_RE.match(w)]
    cand_set = set(candidates) | set(scowl)
    rows = {}
    dropped_typos = []
    for w in candidates:
        f = zipf(w)
        if w in scowl:
            # Dutch compounds make a long tail of rare valid words; keep the useful part.
            floor = MIN_ZIPF_VALID if lang == "en" else 1.5
            if f >= floor or len(w) <= 2:
                rows[w] = (best_case(w, scowl[w]), f, "V")
        elif f >= MIN_ZIPF_INFORMAL:
            if len(w) == 1:
                continue
            if contraction_of(w, cand_set):
                continue  # "dont", "im": handled as replacements, not words
            t = looks_like_typo(w, f, scowl)
            if t:
                dropped_typos.append((w, t))
                continue
            rows[w] = (w, f, "F")
    # Single letters: only "a" and "I" are words.
    for k in list(rows):
        if len(k) == 1 and k not in ("a", "i"):
            del rows[k]
    if lang == "en":
        rows["i"] = ("I", zipf("i"), "V")

    os.makedirs(OUT, exist_ok=True)
    with open(os.path.join(OUT, f"{lang}_words.tsv"), "w", encoding="utf-8") as fh:
        for cased, f, flag in sorted(rows.values(), key=lambda r: (-r[1], r[0])):
            fh.write(f"{cased}\t{int(round(f * 100))}\t{flag}\n")

    # Apostrophe-less contractions -> contraction. Mode = weakest mode that applies it.
    reps = []
    if lang != "en":
        candidates_for_reps = []
    else:
        candidates_for_reps = candidates[:60000]
    known = {r[0].lower(): r[0] for r in rows.values()}
    known_set = set(known)
    for w in candidates_for_reps:
        c = contraction_of(w, known_set)
        if not c or zipf(w) < 2.5 or not CONTRACTION_RE.search(c):
            continue
        target = known[c]
        if w in scowl:  # "cant", "wont", "ill", "well", "were": real words
            if w in ("cant", "wont"):
                reps.append((w, target, "strong"))
            continue
        reps.append((w, target, "balanced"))
    with open(os.path.join(OUT, f"{lang}_replacements.tsv"), "w", encoding="utf-8") as fh:
        for a, b, m in sorted(set(reps)):
            fh.write(f"{a}\t{b}\t{m}\n")

    print(f"words={len(rows)} valid={sum(1 for r in rows.values() if r[2]=='V')} "
          f"informal={sum(1 for r in rows.values() if r[2]=='F')} replacements={len(reps)}")
    print("dropped typos sample:", dropped_typos[:40])


def build_emoji():
    """emoji.tsv: group <TAB> emoji <TAB> search keywords (English + Dutch, CLDR annotations)."""
    import json
    keywords = {}
    for lang in ("en", "nl"):
        path = os.path.join(ROOT, "tools", "data", f"cldr_annotations_{lang}.json")
        ann = json.load(open(path, encoding="utf-8"))["annotations"]["annotations"]
        for e, v in ann.items():
            ks = keywords.setdefault(e.replace("\ufe0f", ""), [])
            for k in v.get("tts", []) + v.get("default", []):
                if k.lower() not in ks:
                    ks.append(k.lower())
    src = os.path.join(ROOT, "tools", "data", "emoji-test.txt")
    group = None
    out = []
    for line in open(src, encoding="utf-8"):
        if line.startswith("# group:"):
            group = line.split(":", 1)[1].strip()
            continue
        if not line.strip() or line.startswith("#") or group == "Component":
            continue
        codes, rest = line.split(";", 1)
        if "fully-qualified" not in rest:
            continue
        cps = [int(c, 16) for c in codes.split()]
        if any(0x1F3FB <= c <= 0x1F3FF for c in cps):
            continue  # skin-tone variants omitted (base emoji only)
        e = "".join(chr(c) for c in cps)
        name = rest.split("#", 1)[1].strip().split(" ", 2)[-1].lower() if "#" in rest else ""
        ks = keywords.get(e.replace("\ufe0f", ""), [])
        words = [name] + [k for k in ks if k != name]
        out.append((group, e, " | ".join(w.replace("\t", " ") for w in words if w)))
    with open(os.path.join(OUT, "emoji.tsv"), "w", encoding="utf-8") as fh:
        for g, e, k in out:
            fh.write(f"{g}\t{e}\t{k}\n")
    print(f"emoji: {len(out)}, with keywords: {sum(1 for o in out if '|' in o[2])}")


if __name__ == "__main__":
    if sys.argv[1] == "emoji":
        build_emoji()
    else:
        main(sys.argv[1], sys.argv[2])
