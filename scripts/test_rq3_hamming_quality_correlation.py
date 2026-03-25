#!/usr/bin/env python3
"""
Tests for the two TODOs in rq3_hamming_quality_correlation.py.

TODO-1  Filename normalisation in hamming_distance()
        The JSON may use bare basenames, repo-relative paths, or full paths;
        all_conflicts.csv may use a different convention.  The match must
        succeed regardless of prefix differences.

TODO-2  Chunk ordering alignment between load_ground_truth() and
        conflictPatterns from the JSON.
        load_ground_truth() sorts by chunkIndex; the variant's
        conflictPatterns list must follow the same order.  The Python side
        can be tested directly; the Java ordering assumption is documented
        here as a contract test.
"""

import csv
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
import rq3_hamming_quality_correlation as rq3


# ── helpers ───────────────────────────────────────────────────────────────────

def _write_conflicts_csv(rows: list[dict]) -> Path:
    tmp = tempfile.NamedTemporaryFile(
        mode="w", suffix=".csv", delete=False, newline=""
    )
    fieldnames = ["merge_id", "filename", "chunkIndex", "y_conflictResolutionResult"]
    writer = csv.DictWriter(tmp, fieldnames=fieldnames, extrasaction="ignore")
    writer.writeheader()
    writer.writerows(rows)
    tmp.close()
    return Path(tmp.name)


# ── TODO-1: Filename normalisation ────────────────────────────────────────────

class TestFilenameNormalisation(unittest.TestCase):
    """
    hamming_distance(variant_patterns, gt_patterns) must correctly match
    files when the JSON and all_conflicts.csv use different path styles.
    """

    def test_exact_filename_match(self):
        """Baseline: identical keys always work."""
        gt      = {"src/Foo.java": ["OURS", "THEIRS"]}
        variant = {"src/Foo.java": ["OURS", "OURS"]}  # second chunk differs
        self.assertEqual(rq3.hamming_distance(variant, gt), 1)

    def test_variant_basename_gt_full_path(self):
        """
        JSON stores bare basename; all_conflicts.csv stores repo-relative path.
        e.g. variant key = "Foo.java", GT key = "src/main/java/Foo.java".
        """
        gt      = {"src/main/java/Foo.java": ["OURS", "THEIRS", "BASE"]}
        variant = {"Foo.java":               ["OURS", "THEIRS", "OURS"]}  # last differs
        self.assertEqual(rq3.hamming_distance(variant, gt), 1)

    def test_variant_full_path_gt_basename(self):
        """
        JSON stores repo-relative path; all_conflicts.csv stores bare basename.
        """
        gt      = {"Foo.java":               ["OURS", "THEIRS"]}
        variant = {"src/main/java/Foo.java": ["THEIRS", "THEIRS"]}  # first differs
        self.assertEqual(rq3.hamming_distance(variant, gt), 1)

    def test_no_overlap_returns_none(self):
        """Completely different filenames → None (zero chunks matched)."""
        gt      = {"Bar.java": ["OURS"]}
        variant = {"Foo.java": ["OURS"]}
        self.assertIsNone(rq3.hamming_distance(variant, gt))

    def test_multiple_files_mixed_path_styles(self):
        """
        Two files: one matches exactly, one only by basename.
        Hamming must sum contributions from both.
        """
        gt = {
            "src/A.java": ["OURS", "OURS"],  # exact-match key
            "src/B.java": ["THEIRS"],         # only reachable via basename
        }
        variant = {
            "src/A.java": ["OURS",   "THEIRS"],  # +1 (second chunk)
            "B.java":     ["THEIRS"],             # +0
        }
        self.assertEqual(rq3.hamming_distance(variant, gt), 1)



# ── TODO-2: Chunk ordering ────────────────────────────────────────────────────

class TestChunkOrdering(unittest.TestCase):
    """
    load_ground_truth() must sort chunks by chunkIndex so that position i in
    the returned list aligns with position i in the variant's conflictPatterns
    list (which VariantProjectBuilder writes in chunkIndex order per file).
    """

    def test_load_ground_truth_sorts_ascending_by_chunk_index(self):
        """
        CSV rows arrive in reverse chunkIndex order (3, 1, 2).
        The loaded list must be in chunkIndex order (1, 2, 3) = OURS, THEIRS, BASE.
        """
        csv_path = _write_conflicts_csv([
            {"merge_id": "abc", "filename": "Foo.java",
             "chunkIndex": "3", "y_conflictResolutionResult": "BASE"},
            {"merge_id": "abc", "filename": "Foo.java",
             "chunkIndex": "1", "y_conflictResolutionResult": "OURS"},
            {"merge_id": "abc", "filename": "Foo.java",
             "chunkIndex": "2", "y_conflictResolutionResult": "THEIRS"},
        ])
        try:
            gt = rq3.load_ground_truth(csv_path)
            self.assertEqual(gt["abc"]["Foo.java"], ["OURS", "THEIRS", "BASE"])
        finally:
            csv_path.unlink()

    def test_hamming_zero_when_variant_matches_chunk_index_order(self):
        """
        If the variant's pattern list is in the same chunkIndex order as the
        sorted GT, Hamming distance must be 0.

        CSV (out of order): chunkIndex 2→THEIRS, 1→OURS.
        Variant (chunkIndex order): ["OURS", "THEIRS"].
        Correct Hamming = 0.  If load_ground_truth used insertion order the
        GT would be ["THEIRS", "OURS"] and Hamming would be 2.
        """
        csv_path = _write_conflicts_csv([
            {"merge_id": "xyz", "filename": "Bar.java",
             "chunkIndex": "2", "y_conflictResolutionResult": "THEIRS"},
            {"merge_id": "xyz", "filename": "Bar.java",
             "chunkIndex": "1", "y_conflictResolutionResult": "OURS"},
        ])
        try:
            gt = rq3.load_ground_truth(csv_path)
            variant = {"Bar.java": ["OURS", "THEIRS"]}  # chunkIndex order
            self.assertEqual(rq3.hamming_distance(variant, gt["xyz"]), 0)
        finally:
            csv_path.unlink()

    def test_hamming_nonzero_detected_with_correct_ordering(self):
        """
        When one chunk genuinely differs, Hamming = 1 regardless of CSV order.
        Guards against the failure mode where sorting hides a real mismatch.
        """
        csv_path = _write_conflicts_csv([
            {"merge_id": "m1", "filename": "X.java",
             "chunkIndex": "2", "y_conflictResolutionResult": "THEIRS"},
            {"merge_id": "m1", "filename": "X.java",
             "chunkIndex": "1", "y_conflictResolutionResult": "OURS"},
        ])
        try:
            gt = rq3.load_ground_truth(csv_path)
            # chunk1=OURS (match), chunk2=BASE (mismatch → Hamming +1)
            variant = {"X.java": ["OURS", "BASE"]}
            self.assertEqual(rq3.hamming_distance(variant, gt["m1"]), 1)
        finally:
            csv_path.unlink()



if __name__ == "__main__":
    unittest.main()
