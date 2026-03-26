# RQ3 Metrics Plan [DRAFT — needs additional metrics and further thought]

RQ3 asks: **How good are the generated variants, and can automated resolution match or exceed the human-merged baseline?**

---

## Metrics

### 1. Variant quality distribution per mode
- For each mode, report the distribution of the best variant quality achieved per merge.
- Quality tiers (in order): full test pass > partial test pass > compile-only > no compile.
- Stacked bar chart per mode showing what fraction of merges reach each tier.

### 2. Match/exceed human baseline rate
- % of merges where at least one variant reaches the same quality tier as the human baseline, per mode.
- Secondary: % where a variant strictly exceeds the baseline (e.g., baseline compiles but fails tests; variant passes tests).
- Broken-baseline merges (`baselineBroken=true`) are especially interesting here: a variant that compiles is already a strict improvement.

### 3. Time to best-quality variant
- Wall-clock time from experiment start until the variant that achieves the highest quality score within the budget is produced, per merge per mode.
- Reports how quickly modes converge to their best outcome, not just whether they find it.
- CDF across merges. Stat test: Wilcoxon signed-rank paired by merge.

### 4. Quality vs. variant index (generator ordering validation)
- For each merge, what is the quality of variant k as a function of k?
- Aggregate: mean/median quality score at variant index 1, 2, 3, ...
- Expected: quality decreases over index (heuristic ordering from RQ1 puts best candidates first). A flat or increasing curve would call the generator into question.
- Separate curves per mode.

### 5. Hamming distance vs. build quality (anti-correlation)
- Already partially implemented in `scripts/rq3_hamming_quality_correlation.py`.
- Hamming distance between a variant's pattern assignment and the human resolution.
- Spearman correlation between distance and quality score.
- Expected: closer to human resolution = higher quality. Validates that the human resolution is a useful proximity signal.

### 6. Necessity of exotic (compound) patterns
- "Exotic" patterns are the 12 compound assignments beyond the 4 atomics (OURS, THEIRS, BASE, EMPTY): the 6 ordered 2-combos (OURSTHEIRS, THEIRSOURS, ...) and 6 ordered 3-combos (OURSTHEIRSBASE, ...).
- For each merge where the best variant improves on or matches the human baseline, check whether that variant's `conflictPatterns` assignment contains at least one exotic pattern in any chunk.
- Report: % of successful/improving merges that required an exotic pattern to achieve their result. Broken down per mode and per quality tier.
- Secondary: among merges where only atomic-pattern variants were tried, what quality tier was reached? Compares the ceiling achievable without compound patterns.
- Motivation: if exotic patterns are rarely needed, the search space could be pruned to the 4 atomics (reducing P^N from 16^N to 4^N) without significant quality loss.

### 7. Manual exploration of notable cases
- Identify and inspect merges where:
  - A variant strictly exceeds the human baseline (human baseline broken or failing tests, variant passes).
  - No variant compiles despite many attempts (hard cases).
  - The best variant is found very late in the index order (generator ordering failed).
- Purpose: sanity-check automated metrics and surface qualitative insights for the paper.

---

## Open questions / missing metrics

- How to define a single scalar quality score for ranking variants (needed for metrics 3 and 4)?
- Should RQ3 compare modes against each other on quality, or only report absolute quality per mode?
- Is there a meaningful metric around *diversity* of the variant set (how different are the top-k variants from each other)?
- Statistical tests for metrics 1 and 2 are not specified yet.
- Consider whether the fold-correct ML-AR inference (fold assignment per merge) should be surfaced as an explicit metric or just noted as a methodological safeguard.
