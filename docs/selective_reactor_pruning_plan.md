# Selective Reactor Pruning — Plan (Phase 1)

> **Status (2026-05-04):** revised after empirical mockup tests on `para a9e67cc8`. Original `-am`-based design abandoned.

## Motivation

The maven-build-cache extension is structurally inadequate for the variant-search workload:

- It hashes `src/test/` into the module's overall hash, so a conflict in `src/test/` of an upstream module flips that module's hash on every variant.
- It propagates upstream module hashes into downstream modules transitively, so a single test-file conflict in one upstream module invalidates *every* downstream module's cache.

Validated empirically on `9ee6f46` (ta4j): conflict in `ta4j-core/src/test/` → both modules hash-miss for every variant; donor `target/` is discarded as stale; zero compile work skipped. On `a9e67cc8` (erudika/para): conflict in `para-server/src/main/` → upstream hashes stable, ~30% wall reduction in cache_sequential.

The cache extension only delivers value in the leaf-`src/main/`-conflict case. We can do better by replacing its hashing entirely with orchestrator-level reasoning: *we already know which files the variants touch*.

## Empirical baseline (para a9e67cc8, 4 modules, JDK 1.8, warm m2)

| Build | Wall (s) | Reactor | Tests on upstream? |
|---|---|---|---|
| `mvn verify` (full) | 75.8 | 4 modules | yes |
| `mvn -pl para-server verify` (no `-am`) | 52.7 | 1 module | **no** — upstream resolved from `~/.m2/` ✓ |
| `mvn -pl para-server -am verify` | 51.0 | 3 modules (no para-war) | **yes** — upstream tests ran |
| `mvn install -DskipTests` (donor bootstrap) | 16.3 | 4 modules | n/a — `+5.4s` over `package` |

Reduction from pruning on para: **30.5% wall** (matches the cache-extension number, but deterministically).

Cache-extension toggle confirmed: `-Dmaven.build.cache.enabled=false` produces an explicit `Cache disabled by command line flag` log line on the bundled `maven-build-cache-extension 1.2.2`.

## Corpus profile

384 root poms, 220 (57%) multi-module. Module-count distribution: median = 2, p75 = 9, p90 = 22, max = 244. Half the corpus has ≤2 modules, where pruning saves nothing meaningful.

Realistic wall-time impact dataset-wide: **5–15%** average, with a long tail of multi-module projects (~10% of corpus has ≥10 modules) seeing 30–60%.

CI profile-flag exposure: 28/443 mvn-using yamls (~6%) pass `-P<profile>`; almost all are deploy/release profiles unrelated to test path. `CiConfigReader` already strips them. Not a phase-1 blocker.

## Design

### Per-merge analysis (one-time)

1. Read conflict file paths from `ConflictFile.classPath` (already known per merge).
2. For each conflict file, walk up to nearest enclosing `pom.xml` → that file's *module*.
3. Compute downstream closure by parsing `<module>` and `<dependency>` from each pom: `affected = closure_downstream({module(f) : f is conflict file})`.
4. Modules **not** in `affected` are bit-identical across all variants and need not re-run.

### Per-variant Maven invocation

- **Donor variant** (the first one run in each cache mode, serialized): full reactor, goal = `install` (not `package`). Populates the per-thread `~/.m2/` with all module jars. Cache extension stays enabled (no harm on empty cache).
- **All subsequent variants**: `mvn -B -fae -pl <affected> -Dmaven.build.cache.enabled=false <goal>`.
  - **No `-am`.** Empirical confirmation: `-am` re-executes the full lifecycle on upstream modules (compile + tests). Upstream deps must come from `~/.m2/` only.
  - Cache extension OFF — it adds overhead with no payoff once we're pruning by construction.

### Test-count inheritance (mandatory)

`TestTotal` currently aggregates all `**/target/surefire-reports/*.txt` and `**/failsafe-reports/*.txt` project-wide (one project-wide tally). With `-pl <affected>`, only affected modules' reports exist, so the variant would report dramatically fewer tests than the donor — wrecking variant ranking.

Required change: bucket per-module test counts at parse time. On donor build, persist `Map<ModulePath, TestTotal>`. On a pruned variant, the variant's reported `TestTotal` = sum of (donor's per-module counts for skipped modules) + (variant's parsed counts for built modules).

### Per-thread `~/.m2/` reuse

Already in place: `VariantExecutionEngine.acquireThreadM2Overlay()` gives each worker thread its own m2 overlay, reused across variants on that thread, cleaned up at mode boundary. Donor-built jars in that overlay are visible to every variant the same thread runs.

The donor must finish before any variant on any thread starts. Cleanest: serialize donor as a "phase 0" inside the engine's mode loop; only after `install` completes does the variant pool start.

## Implementation steps

1. **`ConflictModuleAnalyzer`** (new, in `runner/`):
   - Input: repo root, list of `ConflictFile` paths.
   - Output: ordered `List<String>` of affected module paths (relative to root, e.g. `["para-server", "para-war"]`); empty list = "all modules" (sentinel for parent-pom or outside-any-module conflicts → skip pruning).
   - Steps: (a) walk up from each conflict file to nearest enclosing `pom.xml`; (b) build module → downstream-dependents map by parsing every `pom.xml`'s `<module>`/`<dependency>` (groupId + artifactId match); (c) take downstream closure.
   - Edge cases: conflict in root `pom.xml` → return empty (all-affected sentinel); conflict in file with no enclosing module-pom → return empty; nested submodules → deepest enclosing pom wins.

2. **Per-module `TestTotal`**:
   - In `runner/maven/TestTotal.java`: add `Map<String, ModuleTestTotal> perModule` (key = module relpath, value = same numeric fields). Walk the report tree once, but bucket by enclosing module dir.
   - Backwards compat: existing project-wide totals keep working as the sum across modules.
   - Add a `TestTotal merge(TestTotal donorPerModule, Set<String> skippedModules)` helper that sums donor's counts for skipped + variant's counts for built.

3. **`DonorTracker`** (extend):
   - New field `Map<String, ModuleTestTotal> donorPerModule`, populated when donor variant completes.
   - Accessor for variant code to call when reconstructing its inherited counts.

4. **`VariantExecutionEngine`** (donor-first scheduling):
   - At mode start, if pruning enabled and `affected != all`: serialize donor build (one variant on one thread, full reactor, goal = `install`); wait for completion; record donor's per-module counts. Then fan out remaining variants to the worker pool with the pruned command.
   - If pruning disabled (e.g. `affected == all`): current behavior, no donor-first phase.

5. **Maven command builder** (`AppConfig.buildCommand` / `TwoPhaseRunner`):
   - Add an injection point for `-pl <csv>` and `-Dmaven.build.cache.enabled=false` immediately before the goal.
   - Donor invocation overrides goal to `install`; variant invocation keeps the resolved goal (`verify` or `test`).

6. **Wire-through**:
   - `MavenExecutionFactory.createJustInTimeRunner` → take `affectedModules` + `isDonor` flags.
   - `VariantBuildContext` → carry `affectedModules`.

## Validation strategy

**Pre-merge unit tests** (JUnit, fast):
- `ConflictModuleAnalyzer`: nested submodule mapping; root-pom conflict → empty list; conflict outside any module → empty list; downstream closure correctness on synthetic 5-pom tree; missing-`pom.xml` (deleted file path) handled.
- `TestTotal`: per-module bucketing on synthetic surefire-report tree; `merge()` round-trips correctly; project-wide totals unchanged for non-pruned mode.

**Integration tests** (real Maven, one corpus repo, ~1 min runtime):
- Use `para a9e67cc8` (already validated as pruning-favorable case).
- **Parity test**: run pipeline with pruning OFF and ON; assert same `numCompileSucceeds`, same per-variant `TestTotal`, same donor variant promoted, same `bestVariantIndex`. Wall strictly lower with pruning.
- **Mode-boundary cleanliness**: run mode A then mode B; assert per-thread `~/.m2/` is empty at start of mode B.
- **Cache toggle**: assert non-donor variant logs contain `Cache disabled by command line flag`.

## Realistic expectations

| Project type | Wall reduction |
|---|---|
| Single-module (~half of corpus) | 0% — no pruning possible |
| 2-module, conflict in larger module | ~5% |
| 4-module like para, conflict in dominant module | ~30% (measured) |
| 10+ module, conflict in small leaf | 50–80% (extrapolated) |

Aggregate dataset-wide: **5–15%** is the honest expectation, with a long tail.

## Out of scope

- **Phase 2 (per-module pattern-signature dedup):** deferred indefinitely. Median 2-module repos have nothing to dedup; the cost of implementing module-level `DonorTracker` only pays off if profiling shows the affected-set work itself is the bottleneck.
- **`-P<profile>` propagation:** ~6% of corpus exposed; existing `CiConfigReader` already drops profiles. File a separate issue, do not block phase 1.
- **`-pl` semantics with profile-activated modules:** if a project uses `<modules>` inside an `<profile>`, the affected-module computation may be wrong. Not seen in spot-checks; treat as a known limitation.

## Risks / open questions

- **Downstream closure correctness when poms use `<dependencyManagement>` versus `<dependencies>`:** the closure walk must follow only `<dependencies>` (real edges), not `<dependencyManagement>` (BOM-style version pinning).
- **`install` vs `package` for donor:** measured +5.4s warm. Acceptable; amortized across N variants, contributes < 0.4s/variant overhead.
- **Cross-mode comparability:** pruning changes wall-time variance. The existing RQ2 snapshot becomes incomparable on time metrics — but compile/test counts must remain identical, which the parity test guards.
