# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Components

| Directory | Language | Purpose |
|-----------|----------|---------|
| `cicd-oracle/` | Java 21 / Spring Boot | Core research pipeline — see `cicd-oracle/CLAUDE.md` for full details |
| `plugin/` | Java / Gradle (IntelliJ SDK) | IntelliJ IDEA plugin for interactive conflict resolution |
| `maven-hook/` | Java / Maven | Maven plugin that extends build/test lifecycles for cache support |
| `scripts/` | Python 3 | Standalone analysis and plotting scripts |

## Python Environment

A `.venv/` virtual environment at the repo root contains GPU-enabled PyTorch (required for ML-AR model training). Always use it instead of system `python3` for any ML work — system Python is CPU-only and 10–50× slower.

```bash
source .venv/bin/activate
```

The `run_rq1.sh` pipeline script auto-detects and activates the venv.

## cicd-oracle (Java pipeline)

See [`cicd-oracle/CLAUDE.md`](cicd-oracle/CLAUDE.md) for architecture, build commands, test commands, data directories, and RQ1/RQ2/RQ3 pipeline details.

Quick reference:
```bash
cd cicd-oracle
mvn clean package
mvn test -Dtest=ClassName#methodName
java -DfreshRun=true -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.CiCdMergeResolverApplication
```

## plugin (IntelliJ Plugin)

Built with Gradle. Requires IntelliJ IDEA 2022.3+ and Java 17+.

```bash
cd plugin
./gradlew buildPlugin       # produces zip in build/distributions/
./gradlew runIde            # launches sandbox IDE with plugin loaded (requires Plugin DevKit)
```

A pre-built zip is at `plugin/plugin-zip/demo-1.0-SNAPSHOT.zip` — install via Settings → Plugins → Install from Disk.

## scripts/ (Python Analysis)

Standalone scripts that read Phase 2/3 JSON output; all have defaults pointing to the runtime data directories.

| Script | Purpose |
|--------|---------|
| `plot_results.py` | Produces a multi-chart PDF comparing all 5 experiment modes |
| `rq3_hamming_quality_correlation.py` | RQ3 anti-correlation: Hamming distance vs. build quality (Spearman) |
| `view_noteworthy.py` | Inspects notable variants from experiment output |
| `test_plot_results.py` | Unit tests for `plot_results.py` |
| `test_rq3_hamming_quality_correlation.py` | Unit tests for `rq3_hamming_quality_correlation.py` |

Run a script (defaults to standard data dirs):
```bash
python scripts/plot_results.py
python scripts/plot_results.py /path/to/variant_experiments output.pdf
python scripts/rq3_hamming_quality_correlation.py [rq3_dir] [all_conflicts.csv] [output.pdf]
```

Run script tests with plain `pytest` (no Maven involved):
```bash
cd scripts && python -m pytest test_plot_results.py -v
```

## Code Hygiene

**Remove dead code — do not build parallel ornaments.** When a method, class, or variable is unused, deprecated, or superseded, delete it. Do not rename it to `_unused`, add a `@Deprecated` annotation, or keep it "just in case". The same applies to Python scripts: if a script's logic has been absorbed elsewhere, delete the script. Tests that cover deleted functionality should be deleted or rewritten to cover the replacement.
