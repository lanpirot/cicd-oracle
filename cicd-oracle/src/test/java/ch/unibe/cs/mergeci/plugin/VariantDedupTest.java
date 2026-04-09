package ch.unibe.cs.mergeci.plugin;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
import ch.unibe.cs.mergeci.runner.VariantDedup;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for variant dedup logic in the plugin orchestrator.
 *
 * <p>Covers: simple running, run/stop/run cycles, pinning one or multiple
 * conflicts, MANUAL pinning with version bumps, and combinations of pins
 * with pause/resume.
 */
class VariantDedupTest {

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Build a VariantProject with the given pattern names per chunk.
     * Each chunk is a ConflictBlock with the pattern set via PatternFactory.
     * MergeResult is null since dedup only reads pattern names, not merge content.
     */
    private static VariantProject variant(String... patternNames) {
        ConflictFile cf = new ConflictFile();
        List<IMergeBlock> blocks = new ArrayList<>();
        for (String name : patternNames) {
            ConflictBlock cb = new ConflictBlock(null, Map.of());
            cb.setPattern(PatternFactory.fromName(name));
            blocks.add(cb);
        }
        cf.setMergeBlocks(blocks);
        VariantProject vp = new VariantProject();
        vp.setClasses(List.of(cf));
        return vp;
    }

    /**
     * Shorthand for computeEffectiveAssignment with no manual pins.
     */
    private static List<String> effective(VariantProject v) {
        return VariantDedup.computeEffectiveAssignment(v, Map.of(), Map.of());
    }

    /**
     * Shorthand for computeEffectiveAssignment with manual pins.
     */
    private static List<String> effective(VariantProject v,
                                          Map<Integer, String> manualTexts,
                                          Map<Integer, Integer> manualVersions) {
        return VariantDedup.computeEffectiveAssignment(v, manualTexts, manualVersions);
    }

    // -----------------------------------------------------------------------
    // 1. Basic effective assignment
    // -----------------------------------------------------------------------

    @Test
    void effectiveAssignment_noManualPins() {
        VariantProject v = variant("OURS", "THEIRS", "BASE");
        assertEquals(List.of("OURS", "THEIRS", "BASE"), effective(v));
    }

    @Test
    void effectiveAssignment_withManualPin() {
        VariantProject v = variant("OURS", "THEIRS");
        Map<Integer, String> manualTexts = Map.of(1, "some user text");
        Map<Integer, Integer> manualVersions = Map.of(1, 1);
        assertEquals(List.of("OURS", "MANUAL_v1"), effective(v, manualTexts, manualVersions));
    }

    @Test
    void effectiveAssignment_manualVersionBumpProducesDistinctKey() {
        VariantProject v = variant("OURS", "THEIRS");
        Map<Integer, String> texts = Map.of(1, "text");
        List<String> v1 = effective(v, texts, Map.of(1, 1));
        List<String> v2 = effective(v, texts, Map.of(1, 2));
        assertNotEquals(v1, v2, "Version bump should produce distinct effective key");
        assertEquals("MANUAL_v1", v1.get(1));
        assertEquals("MANUAL_v2", v2.get(1));
    }

    @Test
    void effectiveAssignment_multipleManualPins() {
        VariantProject v = variant("OURS", "THEIRS", "BASE");
        Map<Integer, String> texts = Map.of(0, "text0", 2, "text2");
        Map<Integer, Integer> versions = Map.of(0, 1, 2, 3);
        List<String> eff = effective(v, texts, versions);
        assertEquals(List.of("MANUAL_v1", "THEIRS", "MANUAL_v3"), eff);
    }

    // -----------------------------------------------------------------------
    // 2. Simple running — dedup prevents duplicates
    // -----------------------------------------------------------------------

    @Test
    void dedup_identicalVariantsSkipped() {
        Set<List<String>> seen = new HashSet<>();
        VariantProject v1 = variant("OURS", "THEIRS");
        VariantProject v2 = variant("OURS", "THEIRS"); // identical

        assertTrue(seen.add(effective(v1)), "First should be accepted");
        assertFalse(seen.add(effective(v2)), "Duplicate should be rejected");
    }

    @Test
    void dedup_differentVariantsAccepted() {
        Set<List<String>> seen = new HashSet<>();
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"))));
        assertTrue(seen.add(effective(variant("OURS", "BASE"))));
        assertTrue(seen.add(effective(variant("THEIRS", "OURS"))));
    }

    @Test
    void dedup_allPatternsDistinct() {
        Set<List<String>> seen = new HashSet<>();
        String[] patterns = {"OURS", "THEIRS", "BASE", "EMPTY"};
        for (String p : patterns) {
            assertTrue(seen.add(effective(variant(p))), p + " should be unique");
        }
        assertEquals(4, seen.size());
    }

    // -----------------------------------------------------------------------
    // 3. Run/stop/run — seenEffective persists across pause/resume
    // -----------------------------------------------------------------------

    @Test
    void dedup_persistsAcrossPauseResume() {
        Set<List<String>> seen = new HashSet<>();

        // Run 1: test OURS,THEIRS
        seen.add(effective(variant("OURS", "THEIRS")));

        // Pause + resume (seen is NOT cleared)

        // Run 2: same variant should still be rejected
        assertFalse(seen.add(effective(variant("OURS", "THEIRS"))),
                "Same assignment after pause/resume should still be rejected");

        // Different variant should be accepted
        assertTrue(seen.add(effective(variant("THEIRS", "OURS"))));
    }

    @Test
    void dedup_multiplePauseResumeCycles() {
        Set<List<String>> seen = new HashSet<>();

        // Cycle 1
        seen.add(effective(variant("OURS", "OURS")));
        // pause/resume
        // Cycle 2
        seen.add(effective(variant("THEIRS", "THEIRS")));
        // pause/resume
        // Cycle 3
        assertFalse(seen.add(effective(variant("OURS", "OURS"))), "Cycle 1 variant still rejected");
        assertFalse(seen.add(effective(variant("THEIRS", "THEIRS"))), "Cycle 2 variant still rejected");
        assertTrue(seen.add(effective(variant("BASE", "BASE"))), "New variant accepted");
    }

    // -----------------------------------------------------------------------
    // 4. Pinning one conflict
    // -----------------------------------------------------------------------

    @Test
    void dedup_pinChangesEffectiveAssignment() {
        Set<List<String>> seen = new HashSet<>();
        Map<Integer, String> noTexts = Map.of();
        Map<Integer, Integer> noVersions = Map.of();

        // Before pin: OURS, THEIRS
        VariantProject v = variant("OURS", "THEIRS");
        seen.add(effective(v, noTexts, noVersions));

        // Pin chunk 0 to OURS — generator still produces same variant but
        // effective assignment is same → still rejected
        assertFalse(seen.add(effective(v, noTexts, noVersions)));

        // Pin chunk 0 to MANUAL
        Map<Integer, String> manual0 = Map.of(0, "manual text");
        Map<Integer, Integer> versions0 = Map.of(0, 1);
        assertTrue(seen.add(effective(v, manual0, versions0)),
                "MANUAL pin should produce new effective key");
    }

    @Test
    void dedup_nonManualPinReflectedInPatternName() {
        // When chunk is pinned to OURS, the generator should only produce
        // variants where that chunk has OURS. The effective key uses the
        // pattern name, so if a variant has OURS at chunk 0 (matching the pin),
        // it's the same effective key as before pinning.
        Set<List<String>> seen = new HashSet<>();
        seen.add(effective(variant("OURS", "THEIRS")));

        // Same patterns — still a duplicate
        assertFalse(seen.add(effective(variant("OURS", "THEIRS"))));

        // Different pattern at pinned chunk — different key
        assertTrue(seen.add(effective(variant("THEIRS", "THEIRS"))));
    }

    // -----------------------------------------------------------------------
    // 5. Pinning multiple conflicts
    // -----------------------------------------------------------------------

    @Test
    void dedup_multiplePins() {
        Set<List<String>> seen = new HashSet<>();
        Map<Integer, String> texts = new HashMap<>();
        Map<Integer, Integer> versions = new HashMap<>();

        // No pins
        seen.add(effective(variant("OURS", "THEIRS", "BASE"), Map.of(), Map.of()));

        // Pin chunk 0 to MANUAL v1
        texts.put(0, "text");
        versions.put(0, 1);
        assertTrue(seen.add(effective(variant("OURS", "THEIRS", "BASE"), texts, versions)));

        // Also pin chunk 2 to MANUAL v1
        texts.put(2, "text2");
        versions.put(2, 1);
        assertTrue(seen.add(effective(variant("OURS", "THEIRS", "BASE"), texts, versions)));

        // Same dual pin — duplicate
        assertFalse(seen.add(effective(variant("OURS", "THEIRS", "BASE"), texts, versions)));
    }

    // -----------------------------------------------------------------------
    // 6. MANUAL with different versions
    // -----------------------------------------------------------------------

    @Test
    void dedup_manualVersionBumpAllowsRetest() {
        Set<List<String>> seen = new HashSet<>();
        Map<Integer, String> texts = Map.of(0, "v1 text");

        // Version 1
        seen.add(effective(variant("OURS", "THEIRS"), texts, Map.of(0, 1)));

        // Same text but version bumped → distinct key
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, Map.of(0, 2))),
                "Version bump should allow re-testing");
    }

    @Test
    void dedup_differentManualChunksDifferentVersions() {
        Set<List<String>> seen = new HashSet<>();

        // Chunk 0 manual v1, chunk 1 manual v1
        Map<Integer, String> texts = Map.of(0, "a", 1, "b");
        seen.add(effective(variant("OURS", "THEIRS"), texts, Map.of(0, 1, 1, 1)));

        // Bump only chunk 1
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, Map.of(0, 1, 1, 2))));

        // Bump only chunk 0
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, Map.of(0, 2, 1, 1))));

        // Both bumped — different from all previous
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, Map.of(0, 2, 1, 2))));
    }

    @Test
    void dedup_manualRemovedAndReadded() {
        Set<List<String>> seen = new HashSet<>();

        // Start with manual v1
        Map<Integer, String> texts = new HashMap<>(Map.of(0, "text"));
        Map<Integer, Integer> versions = new HashMap<>(Map.of(0, 1));
        seen.add(effective(variant("OURS", "THEIRS"), texts, versions));

        // Remove manual pin (back to auto)
        texts.clear();
        versions.clear();
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)),
                "Removing manual pin changes effective key");

        // Re-add manual pin with higher version
        texts.put(0, "new text");
        versions.put(0, 2);
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)),
                "Re-adding manual at new version is distinct");
    }

    // -----------------------------------------------------------------------
    // 7. Combined: pins + run/stop/run
    // -----------------------------------------------------------------------

    @Test
    void dedup_pinDuringStopAllowsNewVariants() {
        Set<List<String>> seen = new HashSet<>();

        // Run 1: no pins
        seen.add(effective(variant("OURS", "THEIRS")));
        seen.add(effective(variant("THEIRS", "OURS")));

        // Stop → user pins chunk 0 to MANUAL v1 → resume
        Map<Integer, String> texts = Map.of(0, "manual");
        Map<Integer, Integer> versions = Map.of(0, 1);

        // Same underlying patterns but manual pin changes key
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)));
        assertTrue(seen.add(effective(variant("THEIRS", "OURS"), texts, versions)));

        // But duplicates within this pinned state are still rejected
        assertFalse(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)));
    }

    @Test
    void dedup_pinWhileRunningThenStop() {
        Set<List<String>> seen = new HashSet<>();

        // Variants tested before pin applied
        seen.add(effective(variant("OURS", "THEIRS")));

        // User pins chunk 1 to MANUAL v1 while running — next variants use the pin
        Map<Integer, String> texts = Map.of(1, "manual");
        Map<Integer, Integer> versions = Map.of(1, 1);
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)),
                "Manual pin changes effective key even for same underlying patterns");

        // Stop → resume — the manual-pinned variant is still in seen
        assertFalse(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)));
    }

    @Test
    void dedup_multipleStopCyclesWithChangingPins() {
        Set<List<String>> seen = new HashSet<>();

        // Cycle 1: no pins, test 2 variants
        seen.add(effective(variant("OURS", "THEIRS", "BASE")));
        seen.add(effective(variant("THEIRS", "OURS", "EMPTY")));

        // Stop → pin chunk 0 to OURS (non-manual, pattern name stays same) → resume
        // Variant "OURS, THEIRS, BASE" still rejected (same key)
        assertFalse(seen.add(effective(variant("OURS", "THEIRS", "BASE"))));

        // Stop → pin chunk 2 to MANUAL v1 → resume
        Map<Integer, String> texts = Map.of(2, "fixed code");
        Map<Integer, Integer> versions = Map.of(2, 1);
        assertTrue(seen.add(effective(variant("OURS", "THEIRS", "BASE"), texts, versions)));
        assertTrue(seen.add(effective(variant("THEIRS", "OURS", "EMPTY"), texts, versions)));

        // Stop → edit manual text (bump to v2) → resume
        versions = Map.of(2, 2);
        assertTrue(seen.add(effective(variant("OURS", "THEIRS", "BASE"), texts, versions)));

        // Same v2 again — rejected
        assertFalse(seen.add(effective(variant("OURS", "THEIRS", "BASE"), texts, versions)));
    }

    @Test
    void dedup_exhaustiveRunStopRunScenario() {
        Set<List<String>> seen = new HashSet<>();
        Map<Integer, String> texts = new HashMap<>();
        Map<Integer, Integer> versions = new HashMap<>();

        // === Run 1: no pins ===
        seen.add(effective(variant("OURS", "OURS"), texts, versions));
        seen.add(effective(variant("OURS", "THEIRS"), texts, versions));
        assertEquals(2, seen.size());

        // === Stop ===

        // === Run 2: same variants rejected ===
        assertFalse(seen.add(effective(variant("OURS", "OURS"), texts, versions)));
        // New variant accepted
        seen.add(effective(variant("THEIRS", "THEIRS"), texts, versions));
        assertEquals(3, seen.size());

        // === Stop → pin chunk 0 MANUAL v1 ===
        texts.put(0, "hello");
        versions.put(0, 1);

        // === Run 3: chunk 0 is MANUAL_v1, so effective key only varies by chunk 1 ===
        assertTrue(seen.add(effective(variant("OURS", "OURS"), texts, versions)),
                "MANUAL_v1, OURS is new");
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)),
                "MANUAL_v1, THEIRS is new");
        // variant("THEIRS", "THEIRS") → effective ["MANUAL_v1", "THEIRS"] — same as previous!
        assertFalse(seen.add(effective(variant("THEIRS", "THEIRS"), texts, versions)),
                "Chunk 0 is MANUAL, so underlying pattern is irrelevant — dedup as MANUAL_v1, THEIRS");
        assertEquals(5, seen.size());

        // === Stop → edit manual text → bump to v2 ===
        texts.put(0, "world");
        versions.put(0, 2);

        // === Run 4: same patterns, new version → distinct ===
        assertTrue(seen.add(effective(variant("OURS", "OURS"), texts, versions)));
        assertTrue(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)));
        assertEquals(7, seen.size());

        // === Stop → remove manual pin ===
        texts.clear();
        versions.clear();

        // === Run 5: back to unpinned — variants from Run 1 still in seen ===
        assertFalse(seen.add(effective(variant("OURS", "OURS"), texts, versions)),
                "OURS,OURS without pins was tested in Run 1");
        assertFalse(seen.add(effective(variant("OURS", "THEIRS"), texts, versions)),
                "OURS,THEIRS without pins was tested in Run 1");
        // But new patterns are fine
        assertTrue(seen.add(effective(variant("BASE", "EMPTY"), texts, versions)));
    }

    // -----------------------------------------------------------------------
    // 8. Edge cases
    // -----------------------------------------------------------------------

    @Test
    void dedup_singleChunkVariant() {
        Set<List<String>> seen = new HashSet<>();
        seen.add(effective(variant("OURS")));
        assertFalse(seen.add(effective(variant("OURS"))));
        assertTrue(seen.add(effective(variant("THEIRS"))));
    }

    @Test
    void dedup_manualVersionZeroForUnbumpedChunk() {
        // A manual pin that was never bumped gets version 0
        Map<Integer, String> texts = Map.of(0, "text");
        Map<Integer, Integer> versions = Map.of(); // no bump yet
        List<String> eff = effective(variant("OURS"), texts, versions);
        assertEquals(List.of("MANUAL_v0"), eff);
    }

    @Test
    void dedup_compoundPatterns() {
        Set<List<String>> seen = new HashSet<>();
        seen.add(effective(variant("OURSTHEIRS")));
        assertTrue(seen.add(effective(variant("THEIRSOURS"))),
                "Different ordering = different pattern name");
        assertFalse(seen.add(effective(variant("OURSTHEIRS"))));
    }
}
