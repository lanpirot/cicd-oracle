#!/usr/bin/env python3
"""
Tests for rq3_hamming_quality_correlation.py.

1.  Path normalisation: both sides normalise repo-relative paths so that
    minor format differences (leading './', trailing whitespace) don't
    prevent matching.

2.  Chunk ordering: load_ground_truth() sorts by chunkIndex; the Java side
    uses a TreeMap in extractPatterns() for deterministic lexicographic file
    order and JGit's top-to-bottom chunk order within each file.
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


# ── Path normalisation ────────────────────────────────────────────────────────

class TestPathNormalisation(unittest.TestCase):
    """
    Both sides use repo-relative paths.  _normalize_path strips leading './'
    and normalises separators so minor formatting differences don't break matching.
    """

    def test_exact_filename_match(self):
        """Baseline: identical keys always work."""
        gt      = {"src/Foo.java": ["OURS", "THEIRS"]}
        variant = {"src/Foo.java": ["OURS", "OURS"]}  # second chunk differs
        self.assertEqual(rq3.hamming_distance(variant, gt), 1)

    def test_leading_dot_slash_stripped(self):
        """'./src/Foo.java' and 'src/Foo.java' must match."""
        gt      = {"src/Foo.java":   ["OURS", "THEIRS"]}
        variant = {"./src/Foo.java": ["OURS", "OURS"]}
        self.assertEqual(rq3.hamming_distance(variant, gt), 1)

    def test_no_overlap_returns_none(self):
        """Completely different filenames → None (zero chunks matched)."""
        gt      = {"Bar.java": ["OURS"]}
        variant = {"Foo.java": ["OURS"]}
        self.assertIsNone(rq3.hamming_distance(variant, gt))

    def test_multiple_files(self):
        """Hamming sums contributions from all matched files."""
        gt = {
            "src/A.java": ["OURS", "OURS"],
            "src/B.java": ["THEIRS"],
        }
        variant = {
            "src/A.java": ["OURS", "THEIRS"],  # +1 (second chunk)
            "src/B.java": ["THEIRS"],           # +0
        }
        self.assertEqual(rq3.hamming_distance(variant, gt), 1)

    def test_different_dirs_same_basename_not_confused(self):
        """Files in different directories with the same basename stay separate."""
        gt = {
            "src/main/Foo.java": ["OURS"],
            "src/test/Foo.java": ["THEIRS"],
        }
        variant = {
            "src/main/Foo.java": ["THEIRS"],  # +1
            "src/test/Foo.java": ["THEIRS"],  # +0
        }
        self.assertEqual(rq3.hamming_distance(variant, gt), 1)



# ── Chunk ordering ────────────────────────────────────────────────────────────

class TestChunkOrdering(unittest.TestCase):
    """
    load_ground_truth() must sort chunks by chunkIndex so that position i in
    the returned list aligns with position i in the variant's conflictPatterns
    list (Java side uses JGit's top-to-bottom order within each file).
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
