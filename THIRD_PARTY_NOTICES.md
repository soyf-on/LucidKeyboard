# Third-party notices

## Bundled data

| Data | Where | Source | Licence |
|---|---|---|---|
| English and Dutch word frequencies | `lexicon/en_words.tsv`, `app/src/main/assets/en_lexicon.bin`, `en_charlm.bin` | [wordfreq](https://github.com/rspeer/wordfreq) 3.1.1 by Robyn Speer, which aggregates Wikipedia, OpenSubtitles, Reddit, Twitter, Google Books, SUBTLEX and other sources | Data: **CC BY-SA 4.0**. The derived word list and binary models are distributed under CC BY-SA 4.0. |
| Spell-checked word list and capitalisation | used to build `lexicon/en_words.tsv` (flag `V`); `tools/data/en_US.dic`, `en_US.aff` | SCOWL / en_US Hunspell dictionary (via LibreOffice dictionaries), © Kevin Atkinson and others | Permissive (MIT-like) SCOWL licence plus the BSD-style Ispell and WordNet notices: full text in `third_party/SCOWL-LICENSE.txt` |
| Dutch spell-checked word list | used to build `lexicon/nl_words.tsv` (flag `V`); `tools/data/opentaal-wordlist.txt` | [OpenTaal](https://github.com/OpenTaal/opentaal-wordlist) Nederlandse woordenlijst | Revised BSD **or** CC BY 3.0 (see `tools/data/OPENTAAL-LICENSE.txt`) |
| Next-word statistics | `lexicon/{en,nl}_bigrams.tsv`, `app/src/main/assets/*_bigrams.bin` | Word-pair counts derived from [Tatoeba](https://tatoeba.org) sentences (English and Dutch exports) | **CC BY 2.0 FR** — attribution: Tatoeba contributors |
| Emoji list | `lexicon/emoji.tsv`, `app/src/main/assets/emoji.tsv` | Unicode `emoji-test.txt` 15.1 | Unicode License v3 |
| Emoji search keywords (English, Dutch) | third column of `lexicon/emoji.tsv` | Unicode [CLDR](https://github.com/unicode-org/cldr-json) annotations | Unicode License v3 |

The glyphs themselves come from the device's system emoji font; no emoji images are bundled.

## Libraries (all Apache License 2.0)

* AndroidX Core, Activity
* Jetpack Compose (UI, Foundation, Material 3)
* Kotlin standard library
* kotlinx.serialization
* JUnit 4 (tests only; Eclipse Public License 1.0)

## References (not bundled)

Apple's WWDC sessions and Human Interface Guidelines were used as design references
only. No Apple assets, code or fonts are included. Not affiliated with Apple.
AOSP LatinIME (Apache 2.0) was consulted as a behavioural reference; no code was copied.
