# CI/CD Merge Oracle Plugin

An IntelliJ IDEA plugin that **automatically resolves merge conflicts** by generating resolution variants from learned heuristic patterns, building and testing each variant in parallel, and presenting results in a live dashboard.

---

## Features

- **Live streaming dashboard** -- variants appear as they complete, sorted by a four-tier quality score (modules built, tests passed, pattern simplicity, variant index). The current best variant is highlighted. Clickable tooltip links navigate directly to failed tests and modules.
- **Chunk-level inspection** -- view each conflict chunk with an OURS / BASE / THEIRS preview. Pin individual chunks to a specific resolution (OURS, THEIRS, BASE, EMPTY, or free-form manual text) to narrow the search space.
- **Consensus indicator** -- shows per-chunk pattern agreement among tied-best variants, helping identify which chunks are settled and which need attention.
- **Variant history** -- every variant ever tested is preserved. Browse, compare, and apply any historical variant at any time.
- **One-click apply** -- apply the best (or any selected) variant directly to the working tree.
- **Maven Daemon support** -- optional toggle for faster builds via `mvnd`.
- **Build optimizations** -- overlayFS (fuse-overlayfs copy-on-write) for fast variant directory creation, donor cache warming (reuse compiled artifacts from the best prior variant), two-phase execution (compile-only gate skips tests for non-competitive variants), and a shared Maven build cache across all variants.
- **Benchmark export** -- variant results are continuously exported to `/tmp/variant-benchmark.json` for offline analysis.

---

## Architecture

```
plugin/
  src/main/java/org/example/cicdmergeoracle/cicdMergeTool/
    model/
      BlockGroup.java            -- groups JGit merge blocks belonging to one working-tree conflict
      ChunkKey.java              -- identifies a conflict chunk (file path + index)
    service/
      BlockGroupComputer.java    -- maps JGit conflict blocks to working-tree conflict groups
      ChunkConsensus.java        -- per-chunk pattern distribution across tied-best variants
      HeuristicGeneratorFactory.java -- loads learned pattern CSV, creates variant generators
      ManualPinOverlay.java      -- overlays user-pinned resolutions onto variant blocks
      OracleSession.java         -- thread-safe session state (best variant, history, pins, pause/resume)
      PluginOrchestrator.java    -- top-level pipeline: parse, generate, build, score, stream
      VariantResult.java         -- immutable record of a completed variant build
    ui/
      ChunkTableModel.java       -- table model for the chunk inspector tab
      ManualEditWorkflow.java    -- manages manual chunk editing lifecycle (temp file, pin, revert)
      MergeResolutionPanel.java  -- main UI (dashboard table, chunk selector, status bar)
      MergeResolutionToolWindowFactory.java -- registers the tool window
      MergeStateListener.java    -- reacts to merge state changes
      VariantTableModel.java     -- table model for the variant dashboard with filtering
    util/
      GitUtils.java              -- conflict parsing, merge-state detection
      MyFileUtils.java           -- directory cleanup
      model/MergeInfo.java       -- merge metadata record
  src/main/resources/
    pattern-heuristics/          -- bundled learned pattern distribution CSV
    icons/                       -- plugin icons
    META-INF/plugin.xml          -- tool window and dependency declarations
  src/test/java/.../service/
    BlockGroupComputerTest.java  -- tests JGit-to-working-tree block grouping logic
  src/test/resources/
    create-mock-repo.sh          -- creates a multi-module Maven repo with merge conflicts
```

The plugin depends on the `cicd-oracle` pipeline library (via `mavenLocal`) for model classes, pattern types, variant scoring, Maven execution, and build optimizations (`OverlayMount`, `DonorTracker`, `TwoPhaseRunner`, `MavenCacheManager`).

---

## Requirements

- **IntelliJ IDEA** 2025.1+ (Community or Ultimate)
- **Java** 17+
- **Maven** (project under test must be Maven-based)
- **Maven Daemon** (optional, for faster variant builds)
- **[Plugin DevKit](https://plugins.jetbrains.com/plugin/22851-plugin-devkit)** (only needed for plugin development)

---

## Installation

### From Compiled ZIP

A pre-built zip is available at [`plugin-zip/cicd-merge-oracle-1.0-SNAPSHOT.zip`](plugin-zip/cicd-merge-oracle-1.0-SNAPSHOT.zip).

1. Go to **Settings > Plugins > Install Plugin from Disk...**
2. Select the `.zip` file
3. Restart IntelliJ IDEA

### From Source

```bash
./gradlew buildPlugin
```

The output `.zip` will be in `build/distributions/`.

---

## Usage

1. Open a Maven project that has an **active merge conflict** (i.e. `MERGE_HEAD` exists).
2. Open the **CI/CD Merge Oracle** tool window (right panel).
3. Click **Run** to start generating and testing resolution variants.
4. Watch variants stream into the **Dashboard** tab, sorted best-first.
5. Switch to the **Chunks** tab to inspect individual conflict chunks and their consensus.
6. Optionally pin chunks you are confident about -- the pipeline restarts for the remaining conflicts.
7. Select a variant and click **Apply Variant** (or double-click a row in History) to write it to the working tree.

---

## Testing

```bash
./gradlew test
```

Tests cover the `BlockGroupComputer`, which maps low-level JGit conflict blocks to user-visible working-tree conflict regions (a single working-tree conflict may span multiple JGit blocks with shared lines between them).

---

## Development

### Sandbox

> Requires Plugin DevKit

```bash
./gradlew runIde
```

### Redeploy Script

Builds the plugin, kills any running IntelliJ, deploys the zip, resets the mock repo to a pristine merge-conflict state, and relaunches IntelliJ. The script does not exit — it launches the IDE in the foreground. Run it in the background or in a separate terminal:

```bash
./redeploy.sh &             # defaults to /tmp/mockRepo, runs in background
./redeploy.sh /path/to/repo
```

The mock repo at `/tmp/mockRepo` is a multi-module Maven project with 6 conflict chunks across `core` and `service` modules. It exercises compile-level variance (partial module success) and test-level variance (partial test pass rates). The script resets the repo on every run, so experiments are always reproducible.

### Build

```bash
./gradlew buildPlugin
```
