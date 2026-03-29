package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.Utility;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for orphaned-baseline cleanup in {@link RQPipelineRunner}.
 * Uses a minimal concrete subclass that skips the real pipeline and only
 * exposes the directory-level cleanup logic.
 */
class RQPipelineRunnerTest extends BaseTest {

    private static final List<Utility.Experiments> ALL_MODES = List.of(
            Utility.Experiments.human_baseline,
            Utility.Experiments.no_cache_no_parallel,
            Utility.Experiments.cache_no_parallel,
            Utility.Experiments.no_cache_parallel,
            Utility.Experiments.cache_parallel
    );

    /**
     * 5 baseline JSONs, only 3 have matching variant JSONs.
     * After runModes the orphaned 2 must be deleted,
     * leaving 3 in every directory and countBaselineJsons == 3.
     */
    @Test
    void orphanedBaselinesAreRemoved() throws Exception {
        Path expDir = AppConfig.TEST_EXPERIMENTS_TEMP_DIR.resolve("orphan_test");
        Files.createDirectories(expDir);

        // --- set up merge infos with 5 distinct commit hashes ---
        String[] commits = {
                "aaaa0000aaaa0000aaaa0000aaaa0000aaaa0000",
                "bbbb1111bbbb1111bbbb1111bbbb1111bbbb1111",
                "cccc2222cccc2222cccc2222cccc2222cccc2222",
                "dddd3333dddd3333dddd3333dddd3333dddd3333",
                "eeee4444eeee4444eeee4444eeee4444eeee4444"
        };

        // First 3 are "good" (present in all variant dirs), last 2 are orphans
        String[] goodCommits = {commits[0], commits[1], commits[2]};
        String[] orphanCommits = {commits[3], commits[4]};

        // Create mode directories and populate JSONs
        Path baselineDir = expDir.resolve("human_baseline");
        Files.createDirectories(baselineDir);

        List<Utility.Experiments> variantModes = ALL_MODES.stream()
                .filter(ex -> !ex.isSkipVariants())
                .toList();

        for (Utility.Experiments ex : variantModes) {
            Files.createDirectories(expDir.resolve(ex.getName()));
        }

        // Write all 5 to human_baseline
        for (String commit : commits) {
            writeMinimalJson(baselineDir, commit);
        }

        // Write only the 3 good ones to every variant mode dir
        for (Utility.Experiments ex : variantModes) {
            Path modeDir = expDir.resolve(ex.getName());
            for (String commit : goodCommits) {
                writeMinimalJson(modeDir, commit);
            }
        }

        // Sanity: 5 baseline files before cleanup
        assertEquals(5, countJsonFiles(baselineDir));

        // Build MergeInfo list for all 5
        List<DatasetReader.MergeInfo> merges = buildMergeInfos(commits);

        // --- create a test subclass that points at our temp dir ---
        TestPipelineRunner runner = new TestPipelineRunner(expDir, ALL_MODES, merges);

        // runModes calls removeOrphanedBaselines at the end
        runner.callRunModes("test/project", merges);

        // Assert: orphans removed, 3 remain in baseline
        assertEquals(3, countJsonFiles(baselineDir));
        for (String good : goodCommits) {
            assertTrue(baselineDir.resolve(good + AppConfig.JSON).toFile().exists(),
                    "Good baseline should survive: " + good);
        }
        for (String orphan : orphanCommits) {
            assertFalse(baselineDir.resolve(orphan + AppConfig.JSON).toFile().exists(),
                    "Orphaned baseline should be deleted: " + orphan);
        }

        // Variant dirs unchanged at 3
        for (Utility.Experiments ex : variantModes) {
            assertEquals(3, countJsonFiles(expDir.resolve(ex.getName())),
                    ex.getName() + " should still have 3 JSONs");
        }

        // countBaselineJsons (used for the processed count) == 3
        assertEquals(3, runner.callCountBaselineJsons());
    }

    // ---- helpers ----

    private void writeMinimalJson(Path dir, String commit) throws IOException {
        // Minimal valid JSON — only needs to be a file ending in .json
        Files.writeString(dir.resolve(commit + AppConfig.JSON),
                "{\"processed\":true,\"mergeCommit\":\"" + commit + "\"}");
    }

    private int countJsonFiles(Path dir) {
        String[] list = dir.toFile().list((d, name) -> name.endsWith(AppConfig.JSON));
        return list == null ? 0 : list.length;
    }

    private List<DatasetReader.MergeInfo> buildMergeInfos(String[] commits) {
        return java.util.Arrays.stream(commits).map(c -> {
            DatasetReader.MergeInfo info = new DatasetReader.MergeInfo();
            info.setMergeCommit(c);
            info.setProjectName("test/project");
            return info;
        }).toList();
    }

    /**
     * Minimal concrete subclass of RQPipelineRunner.
     * Overrides abstract methods and exposes package-private hooks so the test
     * can call {@code runModes} without a real repository or Maven execution.
     */
    private static class TestPipelineRunner extends RQPipelineRunner {

        private final Path expDir;
        private final List<Utility.Experiments> modes;
        private final List<DatasetReader.MergeInfo> merges;

        TestPipelineRunner(Path expDir, List<Utility.Experiments> modes,
                           List<DatasetReader.MergeInfo> merges) {
            this.expDir = expDir;
            this.modes = modes;
            this.merges = merges;
        }

        @Override protected List<DatasetReader.MergeInfo> sampleMerges() { return merges; }
        @Override protected List<Utility.Experiments> modesToRun() { return modes; }
        @Override protected Path experimentDir() { return expDir; }

        /**
         * Calls only {@code removeOrphanedBaselines} — the part we are testing.
         * Skips the real per-mode processing (no repo, no Maven).
         */
        void callRunModes(String projectName, List<DatasetReader.MergeInfo> merges) {
            Path humanBaselineDir = expDir.resolve("human_baseline");
            removeOrphanedBaselines(merges, humanBaselineDir);
        }

        int callCountBaselineJsons() {
            return countBaselineJsons();
        }
    }
}
