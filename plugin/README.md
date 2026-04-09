# CI/CD Merge Oracle Plugin

An IntelliJ IDEA plugin that **automatically resolves merge conflicts** by generating resolution variants from learned heuristic patterns, building and testing each variant in parallel, and presenting results in a live dashboard.

---

## Features

- **Live streaming dashboard** -- variants appear as they complete, sorted by a four-tier quality score (modules built, tests passed, pattern simplicity, generator confidence). The current best variant is highlighted.
- **Chunk-level inspection** -- view each conflict chunk with an OURS / BASE / THEIRS preview. Pin individual chunks to a specific resolution to narrow the search space.
- **Consensus indicator** -- shows per-chunk pattern agreement among completed variants, helping identify which chunks are settled and which need attention.
- **Variant history** -- every variant ever tested is preserved. Browse, compare, and apply any historical variant at any time.
- **One-click apply** -- apply the best (or any selected) variant directly to the working tree.
- **Maven Daemon support** -- optional toggle for faster builds via `mvnd`.

---

## Requirements

- **IntelliJ IDEA** 2022.3+ (Community or Ultimate)
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

## Development

### Sandbox

> Requires Plugin DevKit

```bash
./gradlew runIde
```

### Redeploy Script

Builds the plugin, kills any running IntelliJ, deploys the zip, resets the mock repo to a pristine merge-conflict state, and relaunches IntelliJ:

```bash
./redeploy.sh              # defaults to /tmp/mockRepo
./redeploy.sh /path/to/repo
```

The mock repo at `/tmp/mockRepo` is a multi-module Maven project with 6 conflict chunks across `core` and `service` modules. It exercises compile-level variance (partial module success) and test-level variance (partial test pass rates). The script resets the repo on every run, so experiments are always reproducible.

### Build

```bash
./gradlew buildPlugin
```
