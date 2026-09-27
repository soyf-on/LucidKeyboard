# How adaptive touch targets work

The keys you see never move or change size. What changes is the invisible rule that
turns a touch position into a letter.

## The decision rule

For a tap at position **x**, every nearby letter key *k* (and the space bar) gets a score

```
score(k) = log p(x | k)  +  λ · log P̃(k | what you've typed)
```

* **p(x | k)** — the *touch model*: a 2-D Gaussian describing where your finger lands
  when you aim at key *k*. It starts centred on the drawn key with a spread of about a
  quarter key and, if enabled, learns your personal offset (e.g. "I hit a bit low and to
  the right with my thumb") per key, with backoff to your overall offset while a key has
  few samples (Yin et al., CHI 2013).
* **P̃(k | context)** — the *language prior*: how likely each next character is, given
  the letters of the current word, the dictionary (≈96k words with frequencies), and
  your personal vocabulary. It is computed over a small beam of alternative readings
  of the letters so far, so a slightly-missed earlier letter doesn't poison the
  prediction. For letters that fit no known word, a character 4-gram model gives a weak
  spelling-shape prior instead.
* **λ = strength × confidence** — the strength slider (default 70 %) times how
  confident the prior is (low for unknown words; zero when the cursor is in the middle
  of existing text or in password/URL fields).

The highest score wins. This is the classic Bayesian soft-keyboard formulation
(Goodman et al., IUI 2002).

## Why this makes boundaries move

With two neighbouring keys *i* and *j* whose centres are *d* apart and spread σ, the
line where they tie is where the scores are equal. Solving gives a straight boundary
shifted from the midpoint toward the *less likely* key by

```
shift = σ² · λ · ln(P̃ᵢ / P̃ⱼ) / d
```

After "keyboar", P(d) ≫ P(s), so the d/s boundary slides toward *s*: a tap that lands
just over the line on *s* still produces *d*. After "q", the *u* key gains area the
same way. Nothing is hard-coded per word; it falls out of the probabilities.

## Limits that keep every key typeable

Unconstrained resizing can make keys impossible to type (Gunawardana et al., IUI 2010).
Three limits prevent that:

1. **Prior clamp.** P̃ is floored so no candidate can be more than e³ ≈ 20× less likely
   than the best one. That caps the shift at σ²·λ·3/d ≈ 0.17 key at full strength.
2. **Anchor.** A tap inside the central box of a key (half a key pitch wide and tall —
   a quarter of the key's cell) *always* produces that key, whatever the prediction.
   Deliberate taps on "unlikely" letters (names, slang, codes) work.
3. **Reach cap.** No key's effective area can extend more than a quarter pitch beyond
   its visible cell on either axis, whatever the prior *and* the learned offsets say.

Guarantees (2) and (3) are checked by unit tests that sweep the whole keyboard under
many contexts (`TouchDecoderTest`).

## Timing: decided at touch-down, never revised

* The key is decided once, when your finger touches down, using the prediction made
  *after the previous tap was committed*. That is the key that lights up.
* It is not re-interpreted while your finger is down, or afterwards. The next
  prediction is computed only once the tap is committed.
* With two thumbs overlapping, a new touch commits the previous one first, so order
  and context stay correct.

## Adaptive targets and autocorrect are separate

* **Adaptive targets** decide *each letter* from the touch position (Settings ▸ Typing
  accuracy). Turning them off gives exactly the visible key under your finger.
* **Autocorrect** may replace a *whole word* when you press space (Settings ▸
  Autocorrect, or the sliders button on the keyboard). It can be Off while adaptive
  targets stay on.
* **No double correction.** Autocorrect scores candidate words against your *raw touch
  coordinates*, not against the letters the tap decoder chose. The language model is
  therefore not applied twice to the same evidence, so the two stages can't reinforce
  each other's mistakes.

## Learning, and what it never learns from

Touch offsets are learned only when a word is **confirmed**:

* the word you ended up with is a known word;
* its length matches the number of taps;
* at most one letter was changed by correction (that tap gets half weight);
* you moved on to the next word without editing it (learning waits one word, so an
  immediate undo cancels it);
* you didn't undo an auto-correction for it (a reverted word never teaches the touch
  model);
* not in private mode, a password/URL/e-mail field, or an app that asks keyboards not
  to learn.

Each sample more than 0.85 key from the labelled key is discarded as a mislabel. The
model forgets gradually (effective memory ≈150 taps per key), so it follows you if your
grip changes. Learned offsets are clamped to at most 0.3 key.

**Calibration** (optional, skippable): type a few pangrams; each tap is labelled with the
letter the prompt asked for.

**Reset**: Settings ▸ Typing accuracy ▸ Reset touch model.

## Developer visualisation

Settings ▸ Typing accuracy ▸ *Show effective touch regions* colours every point of the
keyboard by the key it would produce *right now* (more saturated = different from the
visible key). It marks your recent taps (green = anchored, blue = normal, orange = the
decoder chose a different key than the one drawn under your finger) and prints the
last decision's probability, prior confidence and decode time.
