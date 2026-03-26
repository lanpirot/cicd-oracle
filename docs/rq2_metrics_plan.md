# RQ2 Metrics Plan

RQ2 asks: **How efficiently do the different experiment modes explore the variant search space within the time budget?**

---

## Metrics

### 1. Finished variants per mode
- Report mean and median number of variants completed per merge, broken down by mode.
- Pair with a CDF over merges to show the distribution shape (expected to be right-skewed).
- Stat test: Wilcoxon signed-rank on paired per-merge counts (same merge across mode pairs). Report rank-biserial r alongside p-value.

### 2. Budget exhaustion rate
- % of merges that hit the time limit (i.e., variants were still queued when the deadline fired), per mode.
- This separates "more variants" from "hit the wall". A high exhaustion rate means the budget is the binding constraint, not the variant count.

### 3. Time to first compiling variant
- Mean and median wall-clock seconds until the first variant that compiles successfully, per mode.
- Framing: proxy for "time to useful output" without crossing into quality territory (RQ3).
- CDF across merges per mode; modes with faster first-compile are strictly better from a user-waiting perspective.
- Stat test: Wilcoxon signed-rank on paired per-merge times. Bootstrap CI on median difference.

### 4. Speedup from parallelism
- Pairwise comparison of parallel vs. sequential modes at the same cache setting:
  - `cache_parallel` vs. `cache_sequential`
  - `parallel` vs. `no_optimization`
- Metric: geometric mean of (sequential time / parallel time) per merge. Report as speedup ratio with bootstrap CI.
- Expected signal: scales sub-linearly with thread count due to Maven overhead and shared resource contention.

### 5. Speedup from cache
- Pairwise comparison of cached vs. non-cached modes at the same parallelism setting:
  - `cache_parallel` vs. `parallel`
  - `cache_sequential` vs. `no_optimization`
- Same geometric mean speedup ratio + bootstrap CI.

### 6. Cache x parallelism interaction
- 2x2 table of geometric mean throughput (variants/second or 1/time-to-first-compile) across the four non-baseline modes.
- Determines whether the two factors are additive or whether one dominates. If cache provides 3x and parallelism 2x but combined is only 4x, that is sub-additive and worth noting.

### 7. Per-variant wall-clock cost by variant index
- For each mode, plot median per-variant execution time as a function of variant index (1, 2, 3, ...).
- Expected result: cost decreases over variant index because Maven short-circuits on early compilation failure, and later variants tend to fail faster (heuristic ordering puts the most-likely-to-succeed variants first, meaning later ones are progressively less plausible and fail earlier in the build lifecycle).
- Non-cache modes are the cleanest signal here (no warm cache confound). Cache modes show a different curve: first variant is expensive (cache warm-up), subsequent ones are cheaper.
- This directly supports the RQ1 generator choice: if the ordering did not matter, the cost curve would be flat.

### 8. Search space size characterization
- Report the theoretical search space per merge as both N (conflict chunk count) and P^N (patterns^chunks, i.e., the number of distinct variant assignments).
- Summary statistics (mean, median, max) across the dataset give readers a sense of scale.
- Scatter plot: P^N on x-axis (log scale), variants finished on y-axis, one point per merge per mode. Spearman correlation to quantify coverage rate vs. search space size.
- A secondary plot of N alone (linear scale) is useful alongside P^N since N is more intuitive and P^N can be dominated by a few large outliers.
- Shows where the exponential search space becomes the binding constraint regardless of mode, and frames why a smart ordering (RQ1) matters more as N grows — even the best mode can only cover a vanishing fraction of P^N for large N.

### 9. Relative build time vs. search space exploration
- For each variant, compute: relative build time = `ownExecutionSeconds / budgetBasisSeconds`, capped at 10.
- Exploration percentage = `variantIndex / P^N * 100`, plotted on a log scale (since P^N grows exponentially, early variants cover a disproportionately large relative share).
- Scatter plot (or binned median curve) of relative build time (y-axis, 0–10) vs. exploration % (x-axis, log scale), per mode.
- Expected result: relative build time decreases as exploration % increases — later variants are cheaper because they fail earlier in the Maven lifecycle. This is a normalised, search-space-aware restatement of metric 7 that makes the cost reduction legible across merges with very different P^N values.
- Separate curves per mode reveal whether cache flattens the curve (all variants cheap) vs. no-cache (steep early drop).

---

## Notes

- **Broken-baseline merges** (`baselineBroken=true`) have artificially injected time budgets (project-average fallback). Exclude them from throughput metrics or report them as a separate stratum to avoid confounding.
- All mode comparisons are paired (same merge, different mode). Do not use unpaired tests.
- For speedup ratios, use geometric mean (not arithmetic) — ratios are multiplicative, not additive.
- Effect size (rank-biserial r) must accompany every Wilcoxon p-value. A statistically significant result with r < 0.1 is practically meaningless.
