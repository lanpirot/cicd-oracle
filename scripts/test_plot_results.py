#!/usr/bin/env python3
"""
Mockup test for plot_results.py.

Simulates a single merge across all five modes:
  - human_baseline : 17 s, 77/90 tests passed, 79/92 modules built
  - cache_parallel : 100 variants, random finish times >= 0.9 × baseline
  - cache_sequential:  15 variants, same
  - parallel       :  20 variants, same
  - no_optimization:   6 variants, same

All random values use a fixed seed so the test is deterministic.
"""

import json
import random
import sys
import tempfile
import unittest
from pathlib import Path

# ── Make plot_results importable from the scripts/ directory ──────────────────
sys.path.insert(0, str(Path(__file__).parent))
import plot_results as pr   # noqa: E402

# ── Constants ─────────────────────────────────────────────────────────────────
SEED                  = 42
HUMAN_BASELINE_SECS   = 17
TOTAL_MODULES         = 92
TOTAL_TESTS           = 90
HUMAN_MODULES_PASSED  = 79
HUMAN_TESTS_PASSED    = 77
MERGE_COMMIT          = "deadbeef" * 5          # 40-char hex-ish string
PROJECT_NAME          = "mock-project"

VARIANT_COUNTS = {
    "cache_parallel":   100,
    "cache_sequential":  15,
    "parallel":          20,
    "no_optimization":    6,
}


# ── JSON helpers ──────────────────────────────────────────────────────────────

def _module_results(passed: int, total: int = TOTAL_MODULES) -> list:
    """Return `total` moduleResult dicts, first `passed` as SUCCESS."""
    results = []
    for i in range(total):
        results.append({
            "moduleName":  f"module-{i}",
            "status":      "SUCCESS" if i < passed else "FAILURE",
            "timeElapsed": round(0.5 + random.random() * 0.5, 3),
        })
    return results


def _variant(name: str, tests_passed: int, modules_passed: int,
             finish_secs: float) -> dict:
    tests_failed  = TOTAL_TESTS   - tests_passed
    modules_failed = TOTAL_MODULES - modules_passed
    build_ok = modules_passed > 0
    return {
        "variantName": name,
        "compilationResult": {
            "moduleResults": _module_results(modules_passed),
            "buildStatus":   "SUCCESS" if build_ok else "FAILURE",
            "totalTime":     round(finish_secs, 3),
        },
        "testResults": {
            "runNum":       TOTAL_TESTS,
            "failuresNum":  tests_failed,
            "errorsNum":    0,
            "skippedNum":   0,
            "elapsedTime":  round(finish_secs * 0.4, 3),
        },
        "finishedAfterFirstVariantStartSeconds": round(finish_secs, 3),
    }


def _all_merges_json(project: str, merge_variants: list, baseline_secs: int,
                     variants_exec_secs: int) -> dict:
    return {
        "projectName": project,
        "merges": [{
            "mergeCommit":          MERGE_COMMIT,
            "parent1":              "a" * 40,
            "parent2":              "b" * 40,
            "numConflictChunks":    3,
            "humanBaselineSeconds": baseline_secs,
            "totalExecutionTime":   baseline_secs + variants_exec_secs,
            "variantsExecution": {
                "executionTimeSeconds": variants_exec_secs,
                "variants":             merge_variants,
            },
        }],
    }


def build_mock_experiment_dir(tmp_path: Path, rng: random.Random) -> Path:
    """
    Write four mode directories under tmp_path, each with one JSON file
    for PROJECT_NAME.  Returns tmp_path.
    """
    # ── human_baseline ────────────────────────────────────────────────────────
    baseline_variant = _variant(
        "human_baseline",
        tests_passed   = HUMAN_TESTS_PASSED,
        modules_passed = HUMAN_MODULES_PASSED,
        finish_secs    = HUMAN_BASELINE_SECS,
    )
    _write_mode(tmp_path, "human_baseline",
                _all_merges_json(PROJECT_NAME, [baseline_variant],
                                 HUMAN_BASELINE_SECS, HUMAN_BASELINE_SECS))

    # ── variant modes ─────────────────────────────────────────────────────────
    min_finish = 0.9 * HUMAN_BASELINE_SECS          # 15.3 s
    max_finish = 3.0 * HUMAN_BASELINE_SECS          # 51.0 s

    for mode, count in VARIANT_COUNTS.items():
        variants = []
        for i in range(count):
            finish      = rng.uniform(min_finish, max_finish)
            tests_p     = rng.randint(0, TOTAL_TESTS)
            modules_p   = rng.randint(0, TOTAL_MODULES)
            variants.append(_variant(
                f"{PROJECT_NAME}_{i}", tests_p, modules_p, finish
            ))

        total_secs = int(max(v["finishedAfterFirstVariantStartSeconds"]
                             for v in variants))
        _write_mode(tmp_path, mode,
                    _all_merges_json(PROJECT_NAME, variants,
                                     HUMAN_BASELINE_SECS, total_secs))

    return tmp_path


def _write_mode(base: Path, mode: str, data: dict) -> None:
    mode_dir = base / mode
    mode_dir.mkdir(parents=True, exist_ok=True)
    with open(mode_dir / f"{PROJECT_NAME}.json", "w") as f:
        json.dump(data, f, indent=2)


# ── Tests ─────────────────────────────────────────────────────────────────────

class TestPlotResultsMockup(unittest.TestCase):

    def setUp(self):
        self._tmp = tempfile.TemporaryDirectory()
        self.tmp_path = Path(self._tmp.name)
        rng = random.Random(SEED)
        build_mock_experiment_dir(self.tmp_path, rng)

    def tearDown(self):
        self._tmp.cleanup()

    # ── Data loading ──────────────────────────────────────────────────────────

    def test_all_five_modes_loaded(self):
        data = pr.load_all_data(self.tmp_path)
        for mode in pr.MODES:
            self.assertIn(mode, data, f"mode '{mode}' missing from loaded data")

    def test_merge_commit_present_in_all_modes(self):
        data = pr.load_all_data(self.tmp_path)
        for mode in pr.MODES:
            self.assertIn(MERGE_COMMIT, data[mode],
                          f"merge commit missing in mode '{mode}'")

    def test_variant_counts_per_mode(self):
        data = pr.load_all_data(self.tmp_path)
        self.assertEqual(
            len(data["human_baseline"][MERGE_COMMIT]
                ["variantsExecution"]["variants"]), 1)
        for mode, expected in VARIANT_COUNTS.items():
            actual = len(data[mode][MERGE_COMMIT]
                         ["variantsExecution"]["variants"])
            self.assertEqual(actual, expected,
                             f"{mode}: expected {expected} variants, got {actual}")

    # ── Human baseline values ─────────────────────────────────────────────────

    def test_human_baseline_test_stats(self):
        data = pr.load_all_data(self.tmp_path)
        v = data["human_baseline"][MERGE_COMMIT]["variantsExecution"]["variants"][0]
        passed, run = pr.test_stats(v)
        self.assertEqual(run,    TOTAL_TESTS)
        self.assertEqual(passed, HUMAN_TESTS_PASSED)

    def test_human_baseline_module_stats(self):
        data = pr.load_all_data(self.tmp_path)
        v = data["human_baseline"][MERGE_COMMIT]["variantsExecution"]["variants"][0]
        passed, total = pr.module_stats(v)
        self.assertEqual(total,  TOTAL_MODULES)
        self.assertEqual(passed, HUMAN_MODULES_PASSED)

    def test_human_baseline_finish_time_equals_baseline(self):
        data = pr.load_all_data(self.tmp_path)
        v = data["human_baseline"][MERGE_COMMIT]["variantsExecution"]["variants"][0]
        self.assertAlmostEqual(
            v["finishedAfterFirstVariantStartSeconds"],
            HUMAN_BASELINE_SECS, places=2)

    # ── Variant finish times in range ─────────────────────────────────────────

    def test_variant_finish_times_above_minimum(self):
        data = pr.load_all_data(self.tmp_path)
        min_finish = 0.9 * HUMAN_BASELINE_SECS
        for mode in VARIANT_COUNTS:
            for v in data[mode][MERGE_COMMIT]["variantsExecution"]["variants"]:
                t = pr.get_finish_time(v)
                self.assertGreaterEqual(
                    t, min_finish - 1e-6,
                    f"{mode} variant '{v['variantName']}' finishes at {t:.2f}s "
                    f"< minimum {min_finish}s")

    def test_variant_tests_within_bounds(self):
        data = pr.load_all_data(self.tmp_path)
        for mode in VARIANT_COUNTS:
            for v in data[mode][MERGE_COMMIT]["variantsExecution"]["variants"]:
                _, run = pr.test_stats(v)
                self.assertLessEqual(run, TOTAL_TESTS,
                                     f"{mode}: runNum {run} > {TOTAL_TESTS}")

    def test_variant_modules_within_bounds(self):
        data = pr.load_all_data(self.tmp_path)
        for mode in VARIANT_COUNTS:
            for v in data[mode][MERGE_COMMIT]["variantsExecution"]["variants"]:
                passed, total = pr.module_stats(v)
                self.assertLessEqual(total, TOTAL_MODULES,
                                     f"{mode}: totalModules {total} > {TOTAL_MODULES}")
                self.assertLessEqual(passed, total,
                                     f"{mode}: modulesPassed {passed} > total {total}")

    # ── Improvement markers ───────────────────────────────────────────────────

    def test_human_baseline_marker_at_relative_time_one(self):
        data = pr.load_all_data(self.tmp_path)
        module_markers, _ = pr.assemble_plot_data(data, pr.module_stats)
        hb_markers = module_markers.get("human_baseline", [])
        self.assertEqual(len(hb_markers), 1,
                         "human_baseline should have exactly one marker per merge")
        rel_t, rate = hb_markers[0]
        self.assertAlmostEqual(rel_t, 1.0, places=3,
                               msg="human_baseline marker must be at relative time 1.0")
        self.assertAlmostEqual(rate, 1.0, places=3,
                               msg="human_baseline marker must be at relative score 1.0")

    def test_improvement_markers_monotonically_increasing(self):
        """Each mode's markers must be strictly increasing in success rate."""
        data = pr.load_all_data(self.tmp_path)
        for metric_fn in (pr.module_stats, pr.test_stats):
            markers_by_mode, _ = pr.assemble_plot_data(data, metric_fn)
            for mode, markers in markers_by_mode.items():
                rates = [r for _, r in markers]
                for i in range(1, len(rates)):
                    self.assertGreater(
                        rates[i], rates[i - 1],
                        f"{mode}: improvement markers not strictly increasing "
                        f"at index {i}: {rates[i - 1]:.1f} → {rates[i]:.1f}")

    def test_improvement_markers_times_ordered(self):
        """Markers within a mode must be in non-decreasing time order."""
        data = pr.load_all_data(self.tmp_path)
        for metric_fn in (pr.module_stats, pr.test_stats):
            markers_by_mode, _ = pr.assemble_plot_data(data, metric_fn)
            for mode, markers in markers_by_mode.items():
                times = [t for t, _ in markers]
                for i in range(1, len(times)):
                    self.assertLessEqual(
                        times[i - 1], times[i],
                        f"{mode}: marker times not ordered at index {i}")

    # ── PDF generation ────────────────────────────────────────────────────────

    def test_pdf_is_created(self):
        out_pdf = self.tmp_path / "test_output.pdf"
        pr.make_plots(self.tmp_path, out_pdf)
        self.assertTrue(out_pdf.exists(), "output PDF was not created")
        self.assertGreater(out_pdf.stat().st_size, 1000,
                           "output PDF is suspiciously small")


if __name__ == "__main__":
    unittest.main()
