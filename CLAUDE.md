# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Components

| Directory | Language | Purpose |
|-----------|----------|---------|
| `cicd-oracle/` | Java 21 / Spring Boot | Core research pipeline — see `cicd-oracle/CLAUDE.md` for full details |
| `plugin/` | Java / Gradle (IntelliJ SDK) | IntelliJ IDEA plugin for interactive conflict resolution |
| `maven-hook/` | Java / Maven | Maven EventSpy — early-abort gate (`-Dcicd.bestModules`), reactor artifact fixing, sidecar JSON output |
| `scripts/` | Python 3 | Standalone analysis and plotting scripts |

## Python Environment

A `.venv/` virtual environment at the workspace root (`merge++/.venv/`) contains GPU-enabled PyTorch (required for ML-AR model training). Always use it instead of system `python3` for any ML work — system Python is CPU-only and 10–50× slower.

```bash
source .venv/bin/activate
```

The `run_rq1.sh` pipeline script auto-detects and activates the venv. The Java pipeline (`AppConfig.PYTHON_EXECUTABLE`) walks up the directory tree from cwd to locate `.venv/bin/python3` automatically. On a VM provisioned with `scripts/setup_vm.sh`, a CPU-only PyTorch venv is created at the same location.

## cicd-oracle (Java pipeline)

See [`cicd-oracle/CLAUDE.md`](cicd-oracle/CLAUDE.md) for architecture, build commands, test commands, data directories, and RQ1/RQ2/RQ3 pipeline details.

Quick reference:
```bash
cd cicd-oracle
mvn clean package
mvn test -Dtest=ClassName#methodName
java -DfreshRun=true -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.experiment.RQ2PipelineRunner
```

## plugin (IntelliJ Plugin)

Built with Gradle. Requires IntelliJ IDEA 2025.1+ and Java 17+. Depends on the `cicd-oracle` pipeline library via `mavenLocal` for model classes, variant scoring, and build optimizations (overlayFS, donor cache warming, two-phase execution, shared Maven build cache).

```bash
cd plugin
./gradlew buildPlugin       # produces zip in build/distributions/
./gradlew runIde            # launches sandbox IDE with plugin loaded (requires Plugin DevKit)
./redeploy.sh               # build, deploy, reset /tmp/mockRepo, relaunch IntelliJ
```

A pre-built zip is at `plugin/plugin-zip/cicd-merge-oracle-1.0-SNAPSHOT.zip` — install via Settings → Plugins → Install from Disk.

## scripts/ (Python Analysis)

Standalone scripts that read experiment/analysis JSON output; all have defaults pointing to the runtime data directories.

| Script | Purpose |
|--------|---------|
| `plot_results.py` | Produces a multi-chart PDF comparing all 5 experiment modes |
| `rq3_hamming_quality_correlation.py` | RQ3 anti-correlation: Hamming distance vs. build quality (Spearman) |
| `latex_variables.py` | Read-modify-write utility for the shared LaTeX variables CSV |
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

## LaTeX Variables (paper statistics)

All paper-ready numbers live in one shared CSV: `~/data/bruteforcemerge/common/latex_variables.csv` (`name,value,description`, loaded by LaTeX `datatool`). Every exporter **upserts** — via `scripts/latex_variables.py` (Python) or `LatexVariableWriter` (Java) — so rerunning one exporter never clobbers variables owned by another. All exporters read persisted outputs (CSV/JSON on disk); no experiment needs to be re-run to add a variable.

To add a new variable, pick the exporter that already computes the relevant statistic, add the variable there, and rerun it:

**RQ1 (`rqOne*`)** — add to the `lvars` dict in `main()` of `cicd-oracle/src/main/resources/pattern-heuristics/generate_rq1_table.py`, then:
```bash
python3 cicd-oracle/src/main/resources/pattern-heuristics/generate_rq1_table.py \
    ~/data/bruteforcemerge/rq1/results/cv_results.csv 1000
```
The `1000` is the variant cap of the final RQ1 run (it appears in table captions and `rqOneVariantCap`). An optional third argument overrides `all_conflicts.csv` (default: `../../common/all_conflicts.csv` relative to the results dir).

**RQ2/RQ3 mode-level stats (`rqTwo*`/`rqThree*` merge, variant, budget-exhaustion, impact counts)** — add in `_export_latex_variables()` in `scripts/plot_results.py`, then:
```bash
python scripts/plot_results.py ~/data/bruteforcemerge/rq2
python scripts/plot_results.py ~/data/bruteforcemerge/rq3
```
The `rqTwo`/`rqThree` prefix is auto-detected from the directory name; force it with `--rq2`/`--rq3`.

**RQ3 quality metrics (`rqThreeBetter*`, `rqThreeComparableCount`, …)** — add in `StatisticsReporter.exportRQ3LatexVariables()` (per-mode variables like `seqMergeCount` belong in `exportLatexVariables()`), rebuild, then run the analysis phase only:
```bash
cd cicd-oracle && mvn clean package -DskipTests
java -DanalyzeOnly=true -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.experiment.RQ3PipelineRunner
```
`analyzeOnly=true` skips all experiments and re-analyzes the JSON already on disk. This run also invokes `plot_results.py` internally, so it refreshes the RQ3 mode-level variables at the same time. (Remember: never rebuild the jar while a pipeline JVM is running.)

## Code Hygiene

**Remove dead code — do not build parallel ornaments.** When a method, class, or variable is unused, deprecated, or superseded, delete it. Do not rename it to `_unused`, add a `@Deprecated` annotation, or keep it "just in case". The same applies to Python scripts: if a script's logic has been absorbed elsewhere, delete the script. Tests that cover deleted functionality should be deleted or rewritten to cover the replacement.
