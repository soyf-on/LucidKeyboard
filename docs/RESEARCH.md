# Research summary and engineering decisions

This document separates three things: what Apple has **publicly documented**,
what published research / open source **demonstrates**, and what **this project
implements**. Nothing here claims knowledge of Apple's proprietary algorithms.

## 1. Adaptive touch targets

### What Apple documents (WWDC19 session 803, "Designing Great ML Experiences")

- The iOS keyboard gives each key a touch area that machine learning "might
  become bigger or smaller depending on the word you're typing or the way your
  fingers are positioned on the keyboard."
- The visible keys do **not** change: "we're not visibly making these buttons
  any bigger or smaller."
- It is presented as *implicit feedback*: the keyboard personalises from normal
  typing without asking the user to do anything.

That is the full extent of the documented behaviour. The talk does not describe
the model, the limits, or how typing history is weighted.

Apple patent US 8,232,973 ("Method, device, and graphical user interface
providing word recommendations for text input") describes enlarging the
*effective* hit region of likely next keys without changing their appearance
(e.g. after "ele", the "p" target grows). A patent shows an idea Apple filed; it
is **not** evidence that the shipping keyboard works that way.

### What research demonstrates

| Source | Finding used here |
|---|---|
| Goodman, Venolia, Steury, Parker, *Language Modeling for Soft Keyboards*, IUI 2002 | Combine a touch likelihood `p(x \| key)` with a character language model `P(key \| context)` and pick the most probable key. Error rate reduced 1.67–1.87×. This is the core Bayesian formulation. |
| Gunawardana, Paek, Meek, *Usability Guided Key-Target Resizing for Soft Keyboards*, IUI 2010 | Unconstrained resizing can make keys **impossible to type**. *Anchoring* — a region of each key that always yields that key — keeps most of the accuracy gain while restoring predictability. |
| Yin, Ouyang, Partridge, Zhai (Google), *Making Touchscreen Keyboards Adaptive to Keys, Hand Postures, and Individuals — A Hierarchical Spatial Backoff Model Approach*, CHI 2013 | Touch offsets are *systematic* per user, per key and per posture. Per-key Gaussians with hierarchical backoff (global → key) reduced language-model-independent character error by ~13%; they note their numbers are upper bounds on their dataset. |
| AOSP LatinIME (Apache-2.0) | Production example of proximity-based spatial scoring combined with a dictionary decoder, and of revert-on-backspace for auto-corrections. Used as a behavioural reference only; no code copied. |

### Engineering decisions

1. **Bayesian tap decoder.** For a tap at `x`, choose
   `argmax_k  log p(x | k) + λ · log P̃(k | context)`
   where `p(x | k)` is a 2-D Gaussian around the key's (learned) typical touch
   point and `P̃` is a *clamped* character prior. With equal isotropic
   variance σ², the boundary between keys *i* and *j* (centres distance *d*
   apart) is a straight line displaced from the midpoint toward the less-likely
   key by `σ² · λ · ln(P̃ᵢ / P̃ⱼ) / d`. Likely keys therefore gain area smoothly,
   unlikely neighbours lose it, and nothing on screen moves.
2. **Anchoring** (Gunawardana et al.): a tap inside the central core of a visible
   key always produces that key, whatever the prior says. Combined with a cap on
   the prior ratio, this gives a hard geometric guarantee (proved in
   `docs/ADAPTIVE_TARGETS.md` and asserted by unit tests) that no boundary can
   move into a neighbour's core — deliberate unusual letters are always typeable.
3. **Character prior** from a frequency-weighted lexicon trie + the user's own
   vocabulary, marginalised over a small beam of alternative interpretations of
   the preceding taps; falls back to a smoothed character n-gram when the word is
   outside the dictionary, with λ reduced by prior confidence.
4. **Personal touch model** with hierarchical backoff (global offset → per-key
   offset/variance), learned only from confirmed words (Yin et al.).
5. **No double correction.** Word-level autocorrect scores candidate words
   against the *raw touch coordinates*, not against the letters the tap decoder
   chose, so the language model is not applied twice to the same evidence.

## 2. Liquid Glass

### What Apple documents (WWDC25 session 219 "Meet Liquid Glass"; HIG Materials)

- Liquid Glass "bends, shapes, and concentrates light" (lensing) rather than only
  blurring; specular highlights respond to geometry and move on interaction.
- On touch it "illuminates from within", starting under the fingertip; it has a
  gel-like flex, and elements can "lift up" temporarily while pressed.
- Tint and shadow adapt to the content behind; small elements flip light/dark
  to keep glyphs legible.
- Accessibility: *Reduce Transparency* makes it frostier; *Increase Contrast*
  makes elements predominantly black/white with a contrasting border; *Reduce
  Motion* disables elastic behaviour.
- (The HIG Materials page could not be fetched as text in this environment; the
  guidance above is from the WWDC25 session.)

### What an Android keyboard can actually render

- An IME draws in its own window. It **cannot sample other apps' pixels**
  without screen capture, which this project refuses to request.
- Android 12+ offers compositor-side blur: `Window.setBackgroundBlurRadius()`
  blurs whatever is behind the window's background shape. It only works when
  `WindowManager.isCrossWindowBlurEnabled()` is true (device support, not in
  battery saver, user setting) and the app can **not read** the blurred result.
  Therefore true *refraction/lensing* of the content behind is impossible; only
  blur is.
- Behind the keyboard there is usually the app's own background (apps resize
  above the IME), so even real blur shows mostly a soft tint of that app.

### Engineering decisions

- Use `setBackgroundBlurRadius` when available (feature-detected at runtime and
  reacting to the system toggle), otherwise a self-contained frosted backdrop.
- Simulate the *optical cues* we can draw ourselves: layered translucent body,
  bright specular rim on the upper-left edge, darker lower rim for thickness,
  inner sheen, soft contact shadow, and a press glow that starts at the actual
  touch point and spreads. Glass tint follows wallpaper colours
  (`WallpaperManager.getWallpaperColors`, no permission needed).
- Labels are always fully opaque with a contrast floor; key boundaries have a
  rim at every transparency setting.
- Reduce-transparency, reduce-motion, increase-contrast and a *simple renderer*
  (flat, no gradients) are all settings.

## 3. Device

Xiaomi 17 Pro Max: Android 16 with HyperOS 3, 6.9" 1200×2608 LTPO AMOLED,
Snapdragon 8 Elite Gen 5 (GSMArena / devicespecifications). Decisions:
`targetSdk 36`, `minSdk 26`, edge-to-edge-safe insets, no reliance on OEM blur
(feature-detected). HyperOS behaviour that could not be verified without the
device is listed in the README's limitations.

## Sources

- Apple, WWDC19 803 Designing Great ML Experiences — https://developer.apple.com/videos/play/wwdc2019/803/
- Apple, WWDC25 219 Meet Liquid Glass — https://developer.apple.com/videos/play/wwdc2025/219/
- Apple HIG, Materials — https://developer.apple.com/design/human-interface-guidelines/materials
- Android, Create an input method — https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method
- Goodman et al. 2002 — https://www.microsoft.com/en-us/research/wp-content/uploads/2016/02/p194-goodman.pdf
- Gunawardana et al. 2010 — https://www.microsoft.com/en-us/research/publication/usability-guided-key-target-resizing-for-soft-keyboards/
- Yin et al. 2013 — https://research.google.com/pubs/archive/41930.pdf
- US 8,232,973 (via AppleInsider) — https://appleinsider.com/articles/12/07/31/apple_granted_patent_for_predictive_text_input_ui
- IME window blur discussion — https://github.com/DevEmperor/DictateKeyboard/issues/378
- Xiaomi 17 Pro Max specs — https://www.gsmarena.com/xiaomi_17_pro_max-review-2895.php
