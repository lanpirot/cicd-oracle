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
