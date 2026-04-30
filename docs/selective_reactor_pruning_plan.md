# Selective Reactor Pruning — Plan (Phase 1 + 2)

## Motivation

The maven-build-cache extension is structurally inadequate for the variant-search workload:

- It hashes `src/test/` into the module's overall hash, so a conflict in `src/test/` of an upstream module flips that module's hash on every variant.
- It propagates upstream module hashes into downstream modules transitively, so a single test-file conflict in one upstream module invalidates *every* downstream module's cache.

Validated empirically on `9ee6f46` (ta4j): conflict in `ta4j-core/src/test/` → both `ta4j-core` and `ta4j-examples` hash-miss for every variant; donor `target/` is discarded by the extension as stale; zero compile work skipped. Cache benefit: ~0%.

In contrast, on `a9e67cc8` (erudika/para): conflict in `para-server/src/main/` → `para-core` and `para-client` hashes stable, cache extension hits correctly. Cache benefit: ~30% wall reduction in cache_sequential.

The cache extension only delivers value in the leaf-`src/main/`-conflict case. We can do better by replacing its hashing entirely with orchestrator-level reasoning: *we already know which files the variants touch*.

## Phase 1 — Affected-module reactor pruning (per-merge, one-time)

### Pre-merge analysis

For each merge, before running any variant:

1. Read the merge's conflict file paths (already known: `numConflictFiles`, `conflictPatterns`).
2. For each conflict file, walk up to the nearest enclosing `pom.xml` → that file's *module*.
3. `affectedModules = ⋃ {module(f) : f is a conflict file}`.
4. Modules **not** in `affectedModules` are bit-identical across all 16 variants. Build them once on the donor; skip on every other variant.

### Per-variant Maven invocation

- **Donor variant** (the first one in each cache mode): full reactor with `install` (not `package`). This populates the per-thread `~/.m2/` with all modules' jars. The maven-build-cache extension can stay enabled here; it's harmless when the cache is empty.
- **All subsequent variants**: `mvn -B -fae -pl <affectedModules> -am` — only build affected modules and their `-am` (also-make) upstream deps. Maven resolves unaffected upstream modules from `~/.m2/` (populated by donor `install`).

This deterministically eliminates rebuild of provably-unchanged modules. No XML hashing config, no source-tree diffing, no build-cache extension trust required.

### Implementation sketch

- New class `ConflictModuleAnalyzer` (in `runner/` or `model/`):
  - Input: `repoPath`, conflict-file path list.
  - Output: `Set<String>` of module paths (relative to project root).
  - Algorithm: for each conflict file, walk up looking for `pom.xml`. Use the deepest enclosing `pom.xml` directory as the module.

- `MavenExecutionFactory.createJustInTimeRunner`:
  - Compute `affectedModules` once when starting variant processing for a merge.
  - Pass them into the `VariantExecutionEngine` config (new field `affectedModules`).
  - In `VariantExecutionEngine.buildAndTest`, the donor variant builds with `install`; subsequent variants build with `-pl <affected> -am`.
  - Donor's per-thread `~/.m2/` is the canonical state; ensure variants in that thread inherit it (they already do — per-thread `maven.repo.local` is shared within a mode).

- Bootstrap correctness: donor must use `install` not `package`. One-line change in the donor command builder.

- Cleanup: per-thread `~/.m2/` already cleaned at mode boundary alongside `SHARED_CACHE_DIR`. No additional cleanup needed.

### Edge cases

- **Nested submodules**: deepest-enclosing-`pom.xml` rule handles this.
- **Generated sources** (e.g., from a code generator module): if the generator output depends on something in an affected module, `-am` already pulls in transitive deps correctly.
- **Surefire reports for skipped modules**: when a module is skipped via `-pl`, it does not run tests. Either (a) inherit donor's per-module `TestTotal`, or (b) cache the donor's per-module test outcomes and merge into variant's `TestTotal` for unaffected modules. The variant's reported test counts must reflect what *would* have been re-run; otherwise rank statistics are off.
- **Plugin executions tied to specific modules**: `-am` resolves but doesn't necessarily re-run plugin goals on unaffected modules. Confirm with a test build that the parent's lifecycle is honoured.
- **Disable maven-build-cache extension for non-donor variants**: with `-pl <affected> -am`, the cache extension is overhead — pass `-Dmaven.build.cache.enabled=false` or omit the `extensions.xml` injection on non-donor variants.

### Expected impact

| Project | Conflict location | Modules skipped per variant | Estimated wall reduction |
|---|---|---|---|
| ta4j (`9ee6f46`) | `ta4j-core/src/test/` | 1/2 leaf modules (ta4j-examples) | minor — ta4j-core dominates and still rebuilds |
| para (`a9e67cc8`) | `para-server/src/main/` | 2/4 leaf modules (para-core, para-client) | ~30% (matches the cache-extension number we already see — phase 1 just makes it deterministic) |
| Multi-module project, conflict in 1 of 8 leaf modules | varies | 7/8 modules skipped | likely 50–80% |

Phase 1 is **strictly better than the cache extension** in every case where the extension hits, and **strictly better** in the cases where it misses (because phase 1's correctness is by construction, not hashing).

## Phase 2 — Per-module pattern-signature dedup (per-variant)

Within `affectedModules`, multiple variants can still pick *the same* per-chunk patterns for chunks in module M. Two variants with identical per-module pattern signatures produce identical M sources → can share M's compiled output.

### Mechanism

- Maintain `donorByModule: Map<(Module, Signature), VariantPath>`.
- For each variant V, for each affected module M:
  - `signature(V, M) = sortedTuple((chunkId, patternUsed) for chunks in M)`.
  - If `(M, signature)` is already in `donorByModule`, V can skip M (copy donor's `${module}/target/` from the registered variant).
  - Otherwise, V rebuilds M; on success, registers itself as donor for `(M, signature)`.

### Implementation sketch

- Extend `DonorTracker` (or add `ModuleDonorTracker`) with a `Map<(Module, Signature), Path>`.
- Compute per-module signatures from `Variant.extractPatterns()` already returned by the variant generator.
- For each affected module, decide independently whether to skip or rebuild.

### When phase 2 pays off

| Scenario | Phase 2 benefit |
|---|---|
| 1 conflict file in 1 module (most of dataset) | none — only 1 affected module, no dedup possible |
| Conflicts across 3+ modules, 16 variants | possibly halve work in affected set if many variants pick identical patterns within a module |
| Variants generated from heuristic distribution that biases towards a small handful of patterns | maximum payoff — many variants will collide on per-module signatures |

Phase 2 is an optimization on top of phase 1 — only worth implementing if profiling shows the affected set is large enough to matter.

## Recommended sequencing

1. **Ship phase 1** as a follow-up PR. Validate on a few merges (single-conflict simple cases first; multi-module crosswise cases second).
2. **Disable maven-build-cache extension on non-donor variants** as part of the same PR — it becomes redundant.
3. **Defer phase 2** until profiling shows the affected-set work is non-trivial. For most of the dataset (conflicts cluster in a single module) phase 2 has nothing to dedup.

## Risks / open questions

- Per-module test count inheritance: if we skip a module on a variant, do we report its pass/fail count from the donor? This is currently how whole-project cache hits behave (we copy donor's `TestTotal`). Same approach should work for the per-module case.
- `-pl` reactor semantics with profiles: confirm that activating `-Pproduction` (or whatever) on the donor and on the pruned variant produces consistent module sets. Maven occasionally surprises here.
- Phase 1 changes the wall-time variance characteristics of the experiment. Need to re-baseline RQ2 numbers if we ship this — the existing snapshot becomes incomparable.
