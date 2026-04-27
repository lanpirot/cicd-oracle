# Interesting Merges

Case studies of merges that surfaced something noteworthy during RQ2/RQ3 runs — unreachable human resolutions, generator pathologies, POM/XML chunk-level edge cases, anything that helps interpret variant-mode results or guide follow-up experiments.

---

## eclipse-milo/milo @ `37cf15f6ffd2fcc1653199c9584f19449933248f` (merge_id 11915, fold 9)

**Observed:** 2026-04-27, `experimentTag=master` RQ2 run
**Repo:** https://github.com/eclipse-milo/milo
**Shape:** 36 conflict files (13 Java + 13 `pom.xml` + 10 other), **56 conflict chunks**, multi-module reactor with 23 modules.

### 1. Human resolution is unreachable by ML-AR vocabulary (53/56)

Pattern breakdown of the actual merge commit, derived from `human_baseline/.../37cf15f6...json`:

| Pattern | Count |
|---|---:|
| OURS  | 49 |
| THEIRS | 4 |
| **NON** (manual / custom code) | **3** |

The three manual chunks are all in **foundation Java files**:

- `opc-ua-stack/stack-core/src/main/java/org/eclipse/milo/opcua/stack/core/util/ManifestUtil.java`
- `opc-ua-sdk/sdk-server/src/main/java/org/eclipse/milo/opcua/sdk/server/Session.java`
- `opc-ua-stack/stack-core/src/main/java/org/eclipse/milo/opcua/stack/core/channel/SerializationQueue.java`

ML-AR (and the heuristic generator) emit only `OURS / THEIRS / BASE / EMPTY` and their compounds. There is no `MANUAL` pattern, so no variant can reproduce the human's edits at these three chunks. **Best achievable score: 53/56 chunks identical to human; full reactor success is impossible.**

### 2. Chunk-level POM patterning produces ~99% scan failures

ML-AR generated **775 unique pattern sequences** for this merge (CSV `autoregressive_predictions_fold9.csv`). The pipeline submitted 789 of them in 101 s with 20 threads. Of those:

- **781 variants died at "could not read N projects"** — Maven aborted during reactor scan because the merged `pom.xml` was unparseable (duplicated `<artifactId>` tags, mismatched `<parent.relativePath>`, etc.). With 13 conflicting POMs, chunk-level pattern selection routinely produces invalid XML.
- **8 variants** survived scan and reached a Reactor Summary.

Before commit `425f502` ("report scan-phase failures as SCAN_FAILURE"), the 781 abort cases were silently dropped from the JSON because `CompilationResult.buildStatus` was `null` and the pipeline filter excluded that. After the fix they appear with `Status.SCAN_FAILURE` and `successfulModules == 0`.

### 3. The 8 survivors are tightly clustered around all-OURS

| Variant | Diff vs. all-OURS donor | Non-OURS chunks |
|---:|:---|:---|
| 33  | — (donor) | none |
| 95  | 1 chunk  | `[25] ManagedAddressSpace.java: THEIRS` |
| 116 | 1 chunk  | `[41] SerializationQueue.java: THEIRS` *(one of the manual files!)* |
| 442 | 1 chunk  | `[16] dictionary-manager/pom.xml: THEIRS` |
| 601 | 1 chunk  | `[25] ManagedAddressSpace.java: BASE` |
| 619 | 2 chunks | `[25] ManagedAddressSpace.java: THEIRS`, `[28] bsd-core/pom.xml: THEIRS` |
| 677 | 1 chunk  | (compound, starts with OURS) |
| 786 | 1 chunk  | `[42] ManifestUtil.java: THEIRS` *(one of the manual files!)* |

ML-AR's beam is heavily biased toward OURS for milo. The dedup that filters 775 → 8 effective resolutions is correct (the other 767 break XML), so the search is narrowed to a tiny ball around the OURS resolution — not because of dedup over-aggression, but because almost everything else is structurally invalid.

### 4. Best variant outcome (donor, variant 33)

```
Reactor Summary:
  ✓ Eclipse Milo Build Tools          SUCCESS  [pom (parent)]
  ✓ Eclipse Milo - OPC UA (root)      SUCCESS  [pom (aggregator)]
  ✓ milo-opc-ua-stack                 SUCCESS  [pom (aggregator)]
  ✓ milo-guava-dependencies           SUCCESS  [jar — no tests in source]
  ✗ milo-stack-core                   FAILURE  ← ManifestUtil.java:63 illegal start of expression
  ✓ milo-opc-ua-sdk                   SUCCESS  [pom (aggregator)]
  ✓ milo-examples                     SUCCESS  [pom (aggregator)]
  ⏸ 16 others                         SKIPPED  (banned by milo-stack-core failure)
```

`runNum: 0` is **correct** — the only two jar modules that built (`milo-build-tools`, `milo-guava-dependencies`) have no tests in their source; surefire ran in both and reported "No tests to run." All test-bearing modules (`milo-stack-tests`, `milo-sdk-tests`, `milo-integration-tests`, etc.) were SKIPPED because they transitively depend on `milo-stack-core`.

The compile error is in `ManifestUtil.java`, **exactly one of the three chunks the human resolved manually**. So the variant search hit the predictable wall: even if ML-AR had picked THEIRS at chunk 42 (variant 786 did), THEIRS isn't what the human wrote — only MANUAL was.

### Takeaways

- Belongs in the **"unreachable" set** for any non-MANUAL pipeline; do not interpret 0 passing tests as a regression.
- A clean stress test for the **SCAN_FAILURE** reporting path — any future run on this merge should show ~770+ SCAN_FAILURE entries plus a handful of FAILUREs in cache_parallel.
- Motivates a **POM-aware (XML-aware) variant generator** if we ever want to lift the 99% scan-failure rate on POM-heavy merges. For RQ2 this is acceptable (still better than brute force); flag for RQ-level work that targets generator quality.

---

## guoguibing/librec @ `dec8ffce4ea6031262ff8c30b52486d3dda22556` (merge_id 4066)

**Observed:** 2026-04-27, `experimentTag=master` RQ2 run
**Repo:** https://github.com/guoguibing/librec
**Shape:** 3 conflict files (2 java + 1 other), **4 conflict chunks**, 2-module reactor.

### Why it's interesting: **the variant pipeline beats the human "ground truth"**

The human's committed merge resolution **does not compile**. From the human_baseline JSON:

```
buildStatus       : FAILURE
modules           : 1 / 2 successful
totalTime         : 2.863 s
baselineBroken    : true
baselineFailureType: BROKEN_MERGE
```

Human pattern frequencies, 4 / 4 chunks: **all THEIRS** (fully canonical, fully reachable for ML-AR).

So the dataset's "ground truth" is itself a broken merge — the upstream commit at `dec8ffce4...` was committed in a non-compiling state. The variant search reproduces the broken outcome (21 of 336 surviving variants reach 1/2 modules, matching the human), and **also finds resolutions the human did not** that compile both modules and reach the test phase.

### Variant outcomes (cache_parallel, after SCAN_FAILURE reporting)

| Bucket | Count |
|---|---:|
| `SCAN_FAILURE` (variant produced unparseable scaffold) | 315 |
| `FAILURE`, 1/2 modules — matches human | 21 |
| `TIMEOUT` at test phase — *better than human* (filtered from JSON, see below) | 9 |

`variantsExecutionTimeSeconds: 413 s` of a 300 s budget — the post-deadline overhead is the engine teardown discussed in commit `89d92ed`.

### Why the "better than human" variants time out

The librec recommender library's tests literally train ML models — `PMFRecommender`, `FFMRecommender`, etc. — running matrix factorization for 100+ iterations on real datasets:

```
[stdout]    Running net.librec.recommender.cf.rating.FFMTestCase
[stderr] 11:32:53 INFO ArffDataModel: Split data to train Set and test Set
[stderr] 11:33:09 INFO FFMRecommender: iter 0: loss = 32051.86
[stderr] 11:33:27 INFO FFMRecommender: iter 1: loss = 20964.35   ← ~18 s / iter
...
[stderr] 11:36:27 INFO FFMRecommender: iter 10: loss = 12888.02
[INFO] BUILD TIMEOUT
```

Per-iteration cost ≈ 18 s, convergence requires 100+ iterations, so a single FFM test method is 30+ minutes. The variant budget for this merge is the 300 s minimum (because the human baseline's `2.863 s` × `2/1` extrapolation = 6 s, which is below the minimum), and one ML-trained surefire test cannot finish in 300 s no matter how cleanly the variant compiled.

### Caveat: the "we beat the human" data point is *not in the JSON*

Variant outcomes with `Status.TIMEOUT` are filtered out by `MavenExecutionFactory.PipelineLifecycleListener.onVariantComplete` — same shape as the now-fixed `null` filter, but for TIMEOUT specifically. So the JSON shows 21 "matches human" variants but **zero** of the 9 "beats human" TIMEOUT variants. They live only in the live log (`TIMEOUT: build exceeded Ns` lines) and on disk in `~/tmp/bruteforce_tmp/log/librec_*_compilation`. To surface this win in downstream analysis, that filter would need the same SCAN_FAILURE-style loosening.

### Takeaways

- **Variant search > human ground truth on broken merges.** Whenever the dataset's `baselineBroken=true`, the variant pipeline can — and here, did — find resolutions that compile when the committed merge does not. This is a *result*, not a regression, and worth surfacing in RQ2's narrative.
- **Budget normalization underflow.** `max(300, baseline×10)` collapses to 300 s whenever the baseline crashed quickly, even though the *real* successful build time is much larger. For ML-heavy projects (librec is the canonical example) this means variants that succeed compile-wise still time out in tests. Three response options were considered (skip, raise minimum, broken-baseline fallback to `MAVEN_BUILD_TIMEOUT × multiplier`) — chose **option 1 (leave as-is)** for the current run; the TIMEOUT data point is itself meaningful.
- **Filter symmetry.** The SCAN_FAILURE fix surfaced compile-stage abnormal exits; the same logic should apply to test-stage TIMEOUTs if we want the JSON to reflect "variant got further than human". Currently it does not.
