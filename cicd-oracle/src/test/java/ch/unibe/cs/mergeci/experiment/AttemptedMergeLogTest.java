package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class AttemptedMergeLogTest extends BaseTest {

    private Path logFile() {
        return AppConfig.TEST_TMP_DIR.resolve("attempted_merges.csv");
    }

    @Test
    void createsHeaderOnFirstUse() throws IOException {
        Files.createDirectories(AppConfig.TEST_TMP_DIR);
        try (AttemptedMergeLog log = new AttemptedMergeLog(logFile())) {
            // just open and close
        }
        List<String> lines = Files.readAllLines(logFile());
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).startsWith("timestamp,project,"));
    }

    @Test
    void logProcessed_writesFullRow() throws IOException {
        Files.createDirectories(AppConfig.TEST_TMP_DIR);
        MergeOutputJSON result = buildResult("myProject", "abc123", "cache_parallel",
                true, 3, 42);

        try (AttemptedMergeLog log = new AttemptedMergeLog(logFile())) {
            log.logProcessed(result);
        }

        List<String> lines = Files.readAllLines(logFile());
        assertEquals(2, lines.size()); // header + 1 row
        String row = lines.get(1);
        assertTrue(row.contains("myProject"));
        assertTrue(row.contains("abc123"));
        assertTrue(row.contains("cache_parallel"));
        assertTrue(row.contains("PROCESSED"));
        assertTrue(row.contains("true")); // baselineBroken
    }

    @Test
    void logSkipped_writesReason() throws IOException {
        Files.createDirectories(AppConfig.TEST_TMP_DIR);
        try (AttemptedMergeLog log = new AttemptedMergeLog(logFile())) {
            log.logSkipped("proj", "def456", "human_baseline", "infrastructure failure");
        }

        List<String> lines = Files.readAllLines(logFile());
        assertEquals(2, lines.size());
        String row = lines.get(1);
        assertTrue(row.contains("SKIPPED"));
        assertTrue(row.contains("infrastructure failure"));
    }

    @Test
    void logProjectFailure_writesWithEmptyMerge() throws IOException {
        Files.createDirectories(AppConfig.TEST_TMP_DIR);
        try (AttemptedMergeLog log = new AttemptedMergeLog(logFile())) {
            log.logProjectFailure("badRepo", "clone failed: timeout");
        }

        List<String> lines = Files.readAllLines(logFile());
        assertEquals(2, lines.size());
        String row = lines.get(1);
        assertTrue(row.contains("PROJECT_FAILURE"));
        assertTrue(row.contains("badRepo"));
    }

    @Test
    void appendMode_doesNotDuplicateHeader() throws IOException {
        Files.createDirectories(AppConfig.TEST_TMP_DIR);

        // First open: creates header
        try (AttemptedMergeLog log = new AttemptedMergeLog(logFile())) {
            log.logSkipped("p", "m1", "hb", "test");
        }
        // Second open: appends without re-writing header
        try (AttemptedMergeLog log = new AttemptedMergeLog(logFile())) {
            log.logSkipped("p", "m2", "hb", "test2");
        }

        List<String> lines = Files.readAllLines(logFile());
        assertEquals(3, lines.size()); // 1 header + 2 data rows
        assertTrue(lines.get(0).startsWith("timestamp,"));
        assertFalse(lines.get(1).startsWith("timestamp,"));
        assertFalse(lines.get(2).startsWith("timestamp,"));
    }

    @Test
    void logProcessed_bestVariantMetrics() throws IOException {
        Files.createDirectories(AppConfig.TEST_TMP_DIR);
        // Build result with baseline (idx 0) and two variants
        MergeOutputJSON result = new MergeOutputJSON();
        result.setProjectName("proj");
        result.setMergeCommit("aaa");
        result.setMode("parallel");
        result.setBaselineBroken(false);
        result.setNumConflictChunks(2);
        result.setTotalExecutionTime(99);

        List<MergeOutputJSON.Variant> variants = new ArrayList<>();
        // baseline
        MergeOutputJSON.Variant v0 = new MergeOutputJSON.Variant();
        v0.setVariantIndex(0);
        v0.setCompilationResult(CompilationResult.forTest(CompilationResult.Status.FAILURE, List.of()));
        variants.add(v0);
        // variant 1: compiles, 5 passed tests
        MergeOutputJSON.Variant v1 = new MergeOutputJSON.Variant();
        v1.setVariantIndex(1);
        v1.setCompilationResult(CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of()));
        TestTotal tt1 = new TestTotal();
        tt1.setRunNum(10);
        tt1.setFailuresNum(3);
        tt1.setErrorsNum(2);
        tt1.setHasData(true);
        v1.setTestResults(tt1);
        variants.add(v1);
        // variant 2: compiles, 8 passed tests (better)
        MergeOutputJSON.Variant v2 = new MergeOutputJSON.Variant();
        v2.setVariantIndex(2);
        v2.setCompilationResult(CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of()));
        TestTotal tt2 = new TestTotal();
        tt2.setRunNum(10);
        tt2.setFailuresNum(1);
        tt2.setErrorsNum(1);
        tt2.setHasData(true);
        v2.setTestResults(tt2);
        variants.add(v2);

        result.setVariants(variants);

        try (AttemptedMergeLog log = new AttemptedMergeLog(logFile())) {
            log.logProcessed(result);
        }

        List<String> lines = Files.readAllLines(logFile());
        String row = lines.get(1);
        // CSV fields: ...,numVariants,bestModules,bestPassedTests,...
        // 2 variants (excl baseline), best = 1 module, 8 passed tests, 99s
        String[] fields = row.split(",");
        // fields: timestamp,project,mergeCommit,mode,verdict,reason,numChunks,baselineBroken,numVariants,bestModules,bestPassedTests,executionSeconds
        assertEquals("2", fields[8]);  // numVariants
        assertEquals("1", fields[9]);  // bestModules
        assertEquals("8", fields[10]); // bestPassedTests
        assertEquals("99", fields[11]); // executionSeconds
    }

    private MergeOutputJSON buildResult(String project, String commit, String mode,
                                        boolean baselineBroken, int chunks, long execTime) {
        MergeOutputJSON result = new MergeOutputJSON();
        result.setProjectName(project);
        result.setMergeCommit(commit);
        result.setMode(mode);
        result.setBaselineBroken(baselineBroken);
        result.setNumConflictChunks(chunks);
        result.setTotalExecutionTime(execTime);

        List<MergeOutputJSON.Variant> variants = new ArrayList<>();
        MergeOutputJSON.Variant baseline = new MergeOutputJSON.Variant();
        baseline.setVariantIndex(0);
        baseline.setCompilationResult(CompilationResult.forTest(CompilationResult.Status.FAILURE, List.of()));
        variants.add(baseline);

        MergeOutputJSON.Variant v1 = new MergeOutputJSON.Variant();
        v1.setVariantIndex(1);
        v1.setCompilationResult(CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of()));
        TestTotal tt = new TestTotal();
        tt.setRunNum(10);
        tt.setFailuresNum(0);
        tt.setErrorsNum(0);
        tt.setHasData(true);
        v1.setTestResults(tt);
        variants.add(v1);

        result.setVariants(variants);
        return result;
    }
}
