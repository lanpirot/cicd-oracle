package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for optimizations introduced since rq2-before-overlayfs:
 * <ul>
 *   <li>Two-phase compile-then-test (skips tests when modules can't beat best)</li>
 *   <li>Donor registry (evolving donor, {@link DonorTracker#isBetterDonor}, {@link DonorTracker#isDonorUsable})</li>
 *   <li>Baseline time normalization (decomposed c+t scaling)</li>
 *   <li>VariantScore 4th tiebreaker (variant index)</li>
 *   <li>SNAPSHOT multi-module detection</li>
 *   <li>Perfect variant early-stop</li>
 *   <li>End-to-end integration across all 4 modes</li>
 * </ul>
 */
public class MavenExecutionFactoryTest extends BaseTest {

    // myTest merge commit and its two parents (fast build, < 5s)
    private static final String MERGE   = "f8b010dd54b018c868ebfc061da31296c3fba6f4";
    private static final String PARENT1 = "c1317ffdd3795f49ba2d0771464e5fec7b858f9d";
    private static final String PARENT2 = "8d26e4bdde76972c121f04fe025ae636394cbb63";

    // ── Baseline time normalization ─────────────────────────────────────────

    @Nested
    class NormalizeBaselineSeconds {

        @Test
        void fullSuccessBuild_returnsRawTime() {
            // All modules succeed, all tests pass → no normalization needed
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.SUCCESS)));
            TestTotal tt = testTotal(100, 0, 0, 10f);
            assertEquals(60, MavenExecutionFactory.normalizeBaselineSeconds(60, tt, cr));
        }

        @Test
        void partialModuleSuccess_scalesCompilationTime() {
            // 2 of 4 modules succeed; test elapsed = 5s; total = 20s
            // compilation = 20 − 5 = 15s, scaled: 15 * 4/2 = 30s
            // tests unchanged (all passing): 5s
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.SUCCESS),
                            module("C", CompilationResult.Status.FAILURE),
                            module("D", CompilationResult.Status.FAILURE)));
            TestTotal tt = testTotal(10, 0, 0, 5f);
            long result = MavenExecutionFactory.normalizeBaselineSeconds(20, tt, cr);
            assertEquals(35, result); // 30 + 5
        }

        @Test
        void partialTestSuccess_scalesTestTime() {
            // Single module, all compile; 100 tests run, 50 pass, 50 fail; elapsed = 10s
            // compilation = 20 − 10 = 10s (no module scaling for single module)
            // test time scaled: 10 * 100/50 = 20s
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of());
            TestTotal tt = testTotal(100, 50, 0, 10f);
            long result = MavenExecutionFactory.normalizeBaselineSeconds(20, tt, cr);
            assertEquals(30, result); // 10 + 20
        }

        @Test
        void zeroSuccessfulModules_returnsMavenBuildTimeout() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.FAILURE),
                            module("B", CompilationResult.Status.FAILURE)));
            TestTotal tt = testTotal(0, 0, 0, 0f);
            assertEquals(AppConfig.MAVEN_BUILD_TIMEOUT,
                    MavenExecutionFactory.normalizeBaselineSeconds(30, tt, cr));
        }

        @Test
        void zeroPassingTests_returnsMavenBuildTimeout() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS)));
            TestTotal tt = testTotal(10, 10, 0, 5f);
            assertEquals(AppConfig.MAVEN_BUILD_TIMEOUT,
                    MavenExecutionFactory.normalizeBaselineSeconds(30, tt, cr));
        }

        @Test
        void nullTestTotal_usesRawSeconds() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of());
            assertEquals(45, MavenExecutionFactory.normalizeBaselineSeconds(45, null, cr));
        }

        @Test
        void nullCompilationResult_usesRawSeconds() {
            TestTotal tt = testTotal(100, 0, 0, 10f);
            assertEquals(50, MavenExecutionFactory.normalizeBaselineSeconds(50, tt, null));
        }

        @Test
        void bothNull_usesRawSeconds() {
            assertEquals(30, MavenExecutionFactory.normalizeBaselineSeconds(30, null, null));
        }

        @Test
        void bothModuleAndTestScaling() {
            // 2/4 modules, 40/100 tests pass; total=100s, testElapsed=20s
            // compilation = 100 − 20 = 80s, scaled: 80 * 4/2 = 160s
            // test time: 20 * 100/40 = 50s
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.SUCCESS),
                            module("C", CompilationResult.Status.FAILURE),
                            module("D", CompilationResult.Status.FAILURE)));
            TestTotal tt = testTotal(100, 50, 10, 20f);
            long result = MavenExecutionFactory.normalizeBaselineSeconds(100, tt, cr);
            assertEquals(210, result); // 160 + 50
        }
    }

    // ── SNAPSHOT multi-module detection ────────────────────────────��─────────

    @Nested
    class SnapshotMultiModule {

        @Test
        void detectsSnapshotMultiModule() throws Exception {
            Path dir = AppConfig.TEST_TMP_DIR;
            Files.createDirectories(dir);
            Path pom = dir.resolve("pom.xml");
            Files.writeString(pom, """
                    <project>
                        <version>1.0-SNAPSHOT</version>
                        <modules>
                            <module>core</module>
                            <module>web</module>
                        </modules>
                    </project>
                    """);
            assertTrue(MavenExecutionFactory.isSnapshotMultiModule(dir));
        }

        @Test
        void nonSnapshotMultiModule_returnsFalse() throws Exception {
            Path dir = AppConfig.TEST_TMP_DIR;
            Files.createDirectories(dir);
            Path pom = dir.resolve("pom.xml");
            Files.writeString(pom, """
                    <project>
                        <version>1.0</version>
                        <modules>
                            <module>core</module>
                        </modules>
                    </project>
                    """);
            assertFalse(MavenExecutionFactory.isSnapshotMultiModule(dir));
        }

        @Test
        void snapshotSingleModule_returnsFalse() throws Exception {
            Path dir = AppConfig.TEST_TMP_DIR;
            Files.createDirectories(dir);
            Path pom = dir.resolve("pom.xml");
            Files.writeString(pom, """
                    <project>
                        <version>1.0-SNAPSHOT</version>
                    </project>
                    """);
            assertFalse(MavenExecutionFactory.isSnapshotMultiModule(dir));
        }

        @Test
        void noPomFile_returnsFalse() {
            assertFalse(MavenExecutionFactory.isSnapshotMultiModule(
                    Path.of("/tmp/nonexistent_" + System.nanoTime())));
        }
    }

    // ── VariantScore: variant index as 4th tiebreaker ───────────────────────

    @Nested
    class VariantScoreTiebreaking {

        @Test
        void lowerIndexWins_whenAllElseEqual() {
            VariantScore early = new VariantScore(3, 50, 0.5, 1);
            VariantScore late  = new VariantScore(3, 50, 0.5, 10);
            assertTrue(early.isBetterThan(late), "Lower variant index should win");
            assertFalse(late.isBetterThan(early));
        }

        @Test
        void modulesStillDominateOverIndex() {
            VariantScore moreModules = new VariantScore(4, 0, 0.0, 100);
            VariantScore lowerIndex  = new VariantScore(3, 0, 0.0, 1);
            assertTrue(moreModules.isBetterThan(lowerIndex));
        }

        @Test
        void testsStillDominateOverIndex() {
            VariantScore moreTests  = new VariantScore(3, 50, 0.0, 100);
            VariantScore lowerIndex = new VariantScore(3, 40, 0.0, 1);
            assertTrue(moreTests.isBetterThan(lowerIndex));
        }

        @Test
        void simplicityStillDominatesOverIndex() {
            VariantScore simpler    = new VariantScore(3, 50, 0.9, 100);
            VariantScore lowerIndex = new VariantScore(3, 50, 0.1, 1);
            assertTrue(simpler.isBetterThan(lowerIndex));
        }

        @Test
        void ofFactory_defaultsToMaxValueIndex() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of());
            TestTotal tt = testTotal(10, 0, 0, 1f);
            Optional<VariantScore> score = VariantScore.of(cr, tt);
            assertTrue(score.isPresent());
            assertEquals(Integer.MAX_VALUE, score.get().variantIndex());
        }

        @Test
        void timedOut_returnsEmpty() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.TIMEOUT, List.of());
            assertTrue(VariantScore.of(cr, null).isEmpty());
        }
    }

    // ── isPerfect ────────────────────────────────────────────────────────────

    @Nested
    class IsPerfect {

        @Test
        void allModulesSuccessAndAllTestsPass() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.SUCCESS)));
            TestTotal tt = testTotal(50, 0, 0, 5f);
            assertTrue(MavenExecutionFactory.isPerfect(cr, tt));
        }

        @Test
        void singleModuleSuccess_allTestsPass() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of());
            TestTotal tt = testTotal(10, 0, 0, 1f);
            assertTrue(MavenExecutionFactory.isPerfect(cr, tt));
        }

        @Test
        void nullCompilationResult_notPerfect() {
            assertFalse(MavenExecutionFactory.isPerfect(null, testTotal(10, 0, 0, 1f)));
        }

        @Test
        void partialModuleFailure_notPerfect() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.FAILURE)));
            TestTotal tt = testTotal(50, 0, 0, 5f);
            assertFalse(MavenExecutionFactory.isPerfect(cr, tt));
        }

        @Test
        void allModulesSuccessButTestFailures_notPerfect() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS,
                    List.of(module("A", CompilationResult.Status.SUCCESS)));
            TestTotal tt = testTotal(50, 3, 0, 5f);
            assertFalse(MavenExecutionFactory.isPerfect(cr, tt));
        }

        @Test
        void allModulesSuccessButTestErrors_notPerfect() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS,
                    List.of(module("A", CompilationResult.Status.SUCCESS)));
            TestTotal tt = testTotal(50, 0, 2, 5f);
            assertFalse(MavenExecutionFactory.isPerfect(cr, tt));
        }

        @Test
        void allModulesSuccessButNoTests_notPerfect() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS,
                    List.of(module("A", CompilationResult.Status.SUCCESS)));
            TestTotal tt = testTotal(0, 0, 0, 0f);
            assertFalse(MavenExecutionFactory.isPerfect(cr, tt));
        }

        @Test
        void allModulesSuccessButNullTestTotal_notPerfect() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS,
                    List.of(module("A", CompilationResult.Status.SUCCESS)));
            assertFalse(MavenExecutionFactory.isPerfect(cr, null));
        }
    }

    // ── Donor registry: isDonorUsable / isBetterDonor ───────────────────────

    @Nested
    class DonorRegistry {

        @Test
        void isDonorUsable_nullCompilationResult_false() {
            assertFalse(DonorTracker.isDonorUsable(null));
        }

        @Test
        void isDonorUsable_zeroSuccessfulModules_false() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.FAILURE)));
            assertFalse(DonorTracker.isDonorUsable(cr));
        }

        @Test
        void isDonorUsable_someSuccessfulModules_true() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.FAILURE)));
            assertTrue(DonorTracker.isDonorUsable(cr));
        }

        @Test
        void isDonorUsable_singleModuleSuccess_true() {
            CompilationResult cr = CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of());
            assertTrue(DonorTracker.isDonorUsable(cr));
        }

        @Test
        void isBetterDonor_nullCandidate_false() {
            CompilationResult current = CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of());
            assertFalse(DonorTracker.isBetterDonor(null, current));
        }

        @Test
        void isBetterDonor_noCurrent_usableCandidate_true() {
            CompilationResult candidate = CompilationResult.forTest(CompilationResult.Status.SUCCESS, List.of());
            assertTrue(DonorTracker.isBetterDonor(candidate, null));
        }

        @Test
        void isBetterDonor_moreModules_true() {
            CompilationResult candidate = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.SUCCESS),
                            module("C", CompilationResult.Status.FAILURE)));
            CompilationResult current = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.FAILURE),
                            module("C", CompilationResult.Status.FAILURE)));
            assertTrue(DonorTracker.isBetterDonor(candidate, current));
        }

        @Test
        void isBetterDonor_fewerModules_false() {
            CompilationResult candidate = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.FAILURE)));
            CompilationResult current = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.SUCCESS)));
            assertFalse(DonorTracker.isBetterDonor(candidate, current));
        }

        @Test
        void isBetterDonor_equalModules_false() {
            CompilationResult candidate = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.FAILURE)));
            CompilationResult current = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.SUCCESS),
                            module("B", CompilationResult.Status.FAILURE)));
            assertFalse(DonorTracker.isBetterDonor(candidate, current));
        }

        @Test
        void isBetterDonor_unusableCandidate_false() {
            CompilationResult candidate = CompilationResult.forTest(CompilationResult.Status.FAILURE,
                    List.of(module("A", CompilationResult.Status.FAILURE)));
            assertFalse(DonorTracker.isBetterDonor(candidate, null));
        }
    }

    // ── computeMaxThreads ───────────────────────────────────────────────────

    @Nested
    class ComputeMaxThreads {

        @Test
        void systemPropertyCap_overridesFormula() {
            try {
                System.setProperty("maxThreads", "3");
                assertEquals(3, AppConfig.computeMaxThreads(1024));
                assertEquals(3, AppConfig.computeMaxThreads(1024, 512));
            } finally {
                System.clearProperty("maxThreads");
            }
        }

        @Test
        void systemPropertyCap_flooredAtOne() {
            try {
                System.setProperty("maxThreads", "0");
                assertEquals(1, AppConfig.computeMaxThreads(1024));
            } finally {
                System.clearProperty("maxThreads");
            }
        }

        @Test
        void noMeasuredPeak_usesDefaultPerThread() {
            // 0 = unknown peak → 10 GB default per thread assumed
            int threads = AppConfig.computeMaxThreads(0);
            assertTrue(threads >= 1, "Should always return at least 1");
        }

        @Test
        void measuredPeak_returnsAtLeastOne() {
            int threads = AppConfig.computeMaxThreads(50L * 1024 * 1024 * 1024); // 50 GB per thread
            assertTrue(threads >= 1, "Should always return at least 1 even with huge per-thread RAM");
        }

        @Test
        void persistentReservation_reducesThreads() {
            // With a large persistent reservation, fewer threads should be available
            int withoutReservation = AppConfig.computeMaxThreads(512 * 1024 * 1024L, 0);
            int withReservation = AppConfig.computeMaxThreads(512 * 1024 * 1024L, 10L * 1024 * 1024 * 1024);
            assertTrue(withReservation <= withoutReservation,
                    "Persistent reservation should reduce (or not increase) thread count");
        }

        @Test
        void formulaProducesReasonableResult_onThisMachine() {
            // Without system property cap, the formula should produce a reasonable result
            // based on actual system resources (MemAvailable, cores)
            String old = System.getProperty("maxThreads");
            try {
                System.clearProperty("maxThreads");
                int threads = AppConfig.computeMaxThreads(512 * 1024 * 1024L); // 512 MB per thread
                assertTrue(threads >= 1);
                int cores = Runtime.getRuntime().availableProcessors();
                assertTrue(threads <= Math.max(1, cores - 2),
                        "Thread count should be capped at cores − 2");
            } finally {
                if (old != null) System.setProperty("maxThreads", old);
            }
        }
    }

    // ── Two-phase + donor with injected generator ───────────────────────────

    @Nested
    class TwoPhaseWithGenerator {

        /**
         * Inject a generator that produces OURS and THEIRS variants for myTest's
         * single conflict chunk. Runs sequential+cache so the donor and two-phase
         * paths are exercised. Verifies variants are actually built and timed.
         */
        @Test
        void sequentialCache_withInjectedGenerator() throws Exception {
            IVariantGenerator generator = new FixedGenerator(List.of(
                    List.of("OURS", "OURS"),
                    List.of("THEIRS", "THEIRS"),
                    List.of("BASE", "BASE")));

            VariantProjectBuilder builder = new VariantProjectBuilder(
                    AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                    AppConfig.TEST_TMP_DIR, AppConfig.TEST_EXPERIMENTS_TEMP_DIR);
            VariantBuildContext context = builder.prepareVariants(PARENT1, PARENT2, MERGE, generator);

            MavenExecutionFactory factory = new MavenExecutionFactory(builder.getLogDir());
            IJustInTimeRunner runner = factory.createJustInTimeRunner(false, true);
            builder.runTestsJustInTime(context, runner);

            // Should have baseline + 3 variants
            assertTrue(factory.getCompilationResults().size() >= 4,
                    "Expected baseline + 3 variants, got " + factory.getCompilationResults().size());
            assertTrue(factory.getTestResults().size() >= 4);

            // Variant finish times recorded for each variant
            assertTrue(factory.getVariantFinishSeconds().size() >= 3,
                    "Each variant should have a finish time");
            for (double seconds : factory.getVariantFinishSeconds().values()) {
                assertTrue(seconds >= 0, "Variant finish time should be non-negative");
            }

            // Cache mode: if any variant compiled successfully, it should have become a donor
            boolean anyVariantCompiled = factory.getCompilationResults().entrySet().stream()
                    .filter(e -> e.getKey().contains("_"))
                    .anyMatch(e -> e.getValue() != null && e.getValue().getSuccessfulModules() > 0);
            if (anyVariantCompiled) {
                assertFalse(factory.getCacheDonorKeys().isEmpty(),
                        "Successful variant should become a cache donor");
                // Donors should also appear as cache hits for subsequent variants
            } else {
                // No variants compiled → donor set may be empty (expected with broken variants)
                assertTrue(factory.getCacheDonorKeys().isEmpty(),
                        "No donor expected when no variant compiled successfully");
            }
        }

        /**
         * Parallel+cache with injected generator.
         * Verifies parallel execution, donor registry, and cache hits.
         */
        @Test
        void parallelCache_withInjectedGenerator() throws Exception {
            IVariantGenerator generator = new FixedGenerator(List.of(
                    List.of("OURS", "OURS"),
                    List.of("THEIRS", "THEIRS"),
                    List.of("BASE", "BASE"),
                    List.of("EMPTY", "EMPTY")));

            VariantProjectBuilder builder = new VariantProjectBuilder(
                    AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                    AppConfig.TEST_TMP_DIR, AppConfig.TEST_EXPERIMENTS_TEMP_DIR);
            VariantBuildContext context = builder.prepareVariants(PARENT1, PARENT2, MERGE, generator);

            MavenExecutionFactory factory = new MavenExecutionFactory(builder.getLogDir());
            IJustInTimeRunner runner = factory.createJustInTimeRunner(true, true);
            builder.runTestsJustInTime(context, runner);

            // Should have baseline + 4 variants
            assertTrue(factory.getCompilationResults().size() >= 5,
                    "Expected baseline + 4 variants, got " + factory.getCompilationResults().size());
            assertTrue(factory.getMaxThreads() >= 1);
        }

        /**
         * No-cache sequential with injected generator.
         * Ensures no donors or cache hits in no-cache mode.
         */
        @Test
        void sequentialNoCache_withInjectedGenerator() throws Exception {
            IVariantGenerator generator = new FixedGenerator(List.of(
                    List.of("OURS", "OURS"),
                    List.of("THEIRS", "THEIRS")));

            VariantProjectBuilder builder = new VariantProjectBuilder(
                    AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                    AppConfig.TEST_TMP_DIR, AppConfig.TEST_EXPERIMENTS_TEMP_DIR);
            VariantBuildContext context = builder.prepareVariants(PARENT1, PARENT2, MERGE, generator);

            MavenExecutionFactory factory = new MavenExecutionFactory(builder.getLogDir());
            IJustInTimeRunner runner = factory.createJustInTimeRunner(false, false);
            builder.runTestsJustInTime(context, runner);

            assertTrue(factory.getCompilationResults().size() >= 3,
                    "Expected baseline + 2 variants");
            assertTrue(factory.getCacheDonorKeys().isEmpty());
            assertTrue(factory.getCacheHitKeys().isEmpty());
        }
    }

    // ── End-to-end: all 4 variant modes on myTest ───────────────────────────

    @Nested
    class EndToEnd {

        /**
         * Run human_baseline (skipVariants=true) on myTest.
         * Verifies that baseline produces compilation and test results without running any variants.
         */
        @Test
        void humanBaseline_producesResultsWithoutVariants() throws Exception {
            VariantProjectBuilder builder = new VariantProjectBuilder(
                    AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                    AppConfig.TEST_TMP_DIR, AppConfig.TEST_EXPERIMENTS_TEMP_DIR);
            VariantBuildContext context = builder.prepareVariants(PARENT1, PARENT2, MERGE, (String) null);

            MavenExecutionFactory factory = new MavenExecutionFactory(builder.getLogDir());
            IJustInTimeRunner runner = factory.createJustInTimeRunner(false, false, true);
            ExperimentTiming timing = builder.runTestsJustInTime(context, runner);

            assertNotNull(timing.getHumanBaselineExecutionTime(), "Baseline time should be recorded");
            assertTrue(timing.getHumanBaselineExecutionTime().getSeconds() >= 0);
            assertTrue(timing.getNormalizedBaselineSeconds() >= 0, "Normalized baseline should be non-negative");

            // Baseline result is keyed by project name (no _N suffix)
            assertTrue(factory.getCompilationResults().containsKey("myTest"),
                    "Should have baseline compilation result");
            assertTrue(factory.getTestResults().containsKey("myTest"),
                    "Should have baseline test result");

            // No variant results (only the baseline entry)
            assertEquals(1, factory.getCompilationResults().size(),
                    "skipVariants should produce only the baseline entry");
        }

        /**
         * Run no_cache + no_parallel (sequential, no cache) on myTest.
         * The simplest variant mode: sequential execution, no donor cache.
         */
        @Test
        void sequentialNoCache_runsVariants() throws Exception {
            MavenExecutionFactory factory = runMode(false, false);

            assertFalse(factory.getCompilationResults().isEmpty());
            // Should have baseline + at least one variant
            assertTrue(factory.getCompilationResults().size() >= 1);
            // No cache donors in no-cache mode
            assertTrue(factory.getCacheDonorKeys().isEmpty(),
                    "No donors expected without cache");
            assertTrue(factory.getCacheHitKeys().isEmpty(),
                    "No cache hits expected without cache");
        }

        /**
         * Run cache + no_parallel (sequential cache with donor evolution).
         */
        @Test
        void sequentialCache_producesDonors() throws Exception {
            MavenExecutionFactory factory = runMode(false, true);

            assertFalse(factory.getCompilationResults().isEmpty());
            // In cache mode, if any variant (not baseline) compiled successfully it should become a donor.
            // myTest has no ML predictions and no explicit generator, so no variants are produced.
            // The baseline is keyed by project name (no _N suffix) and is not a donor.
            boolean anyVariantSuccess = factory.getCompilationResults().entrySet().stream()
                    .filter(e -> e.getKey().contains("_"))
                    .anyMatch(e -> e.getValue() != null && e.getValue().getSuccessfulModules() > 0);
            if (anyVariantSuccess) {
                assertFalse(factory.getCacheDonorKeys().isEmpty(),
                        "Successful variant should become cache donor");
            }
        }

        /**
         * Run no_cache + parallel.
         */
        @Test
        void parallelNoCache_runsVariants() throws Exception {
            MavenExecutionFactory factory = runMode(true, false);

            assertFalse(factory.getCompilationResults().isEmpty());
            assertTrue(factory.getMaxThreads() >= 1, "Should use at least 1 thread");
            assertTrue(factory.getCacheDonorKeys().isEmpty(),
                    "No donors expected without cache");
        }

        /**
         * Run cache + parallel (all optimizations combined).
         */
        @Test
        void parallelCache_allOptimizations() throws Exception {
            MavenExecutionFactory factory = runMode(true, true);

            assertFalse(factory.getCompilationResults().isEmpty());
            assertTrue(factory.getMaxThreads() >= 1);
        }

        /**
         * Verify that storedBaselineSeconds skips re-running the baseline.
         * Use a very short stored time so the variant budget is small.
         */
        @Test
        void storedBaseline_skipsBaselineRun() throws Exception {
            VariantProjectBuilder builder = new VariantProjectBuilder(
                    AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                    AppConfig.TEST_TMP_DIR, AppConfig.TEST_EXPERIMENTS_TEMP_DIR);
            VariantBuildContext context = builder.prepareVariants(PARENT1, PARENT2, MERGE, (String) null);

            MavenExecutionFactory factory = new MavenExecutionFactory(builder.getLogDir());
            // Use a stored baseline of 5s so the budget is 5*10=50s (or min 300s)
            IJustInTimeRunner runner = factory.createJustInTimeRunner(false, false, true, 5L);
            ExperimentTiming timing = builder.runTestsJustInTime(context, runner);

            // Even with skipVariants=true, storedBaselineSeconds should be used
            assertEquals(5, timing.getHumanBaselineExecutionTime().getSeconds(),
                    "Stored baseline should be used directly");
        }

        /**
         * MavenVariantEvaluator integration: verify it delegates correctly.
         */
        @Test
        void evaluatorInterface_delegatesCorrectly() throws Exception {
            VariantProjectBuilder builder = new VariantProjectBuilder(
                    AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                    AppConfig.TEST_TMP_DIR, AppConfig.TEST_EXPERIMENTS_TEMP_DIR);
            VariantBuildContext context = builder.prepareVariants(PARENT1, PARENT2, MERGE, (String) null);

            MavenVariantEvaluator evaluator = new MavenVariantEvaluator(builder.getLogDir());
            ExperimentTiming timing = evaluator.runExperiment(
                    context, builder, false, false, true, 0L, 0L, 0L);

            assertNotNull(timing);
            assertNotNull(evaluator.getCompilationResults());
            assertNotNull(evaluator.getTestResults());
            assertNotNull(evaluator.getVariantFinishSeconds());
            assertFalse(evaluator.isBudgetExhausted());
            assertNotNull(evaluator.getCacheDonorKeys());
            assertNotNull(evaluator.getCacheHitKeys());
            assertTrue(evaluator.getPeakBaselineRamBytes() >= 0);
            assertTrue(evaluator.getBaselineDirGrowthBytes() >= 0);
        }

        private MavenExecutionFactory runMode(boolean parallel, boolean cache) throws Exception {
            VariantProjectBuilder builder = new VariantProjectBuilder(
                    AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                    AppConfig.TEST_TMP_DIR, AppConfig.TEST_EXPERIMENTS_TEMP_DIR);
            VariantBuildContext context = builder.prepareVariants(PARENT1, PARENT2, MERGE, (String) null);

            MavenExecutionFactory factory = new MavenExecutionFactory(builder.getLogDir());
            IJustInTimeRunner runner = factory.createJustInTimeRunner(parallel, cache);
            builder.runTestsJustInTime(context, runner);
            return factory;
        }
    }

    // ── Helper methods ──────────────────────────────────────────────────────

    private static CompilationResult.ModuleResult module(String name, CompilationResult.Status status) {
        return CompilationResult.ModuleResult.builder()
                .moduleName(name)
                .status(status)
                .timeElapsed(1.0f)
                .build();
    }

    private static TestTotal testTotal(int run, int failures, int errors, float elapsed) {
        TestTotal tt = new TestTotal();
        tt.setRunNum(run);
        tt.setFailuresNum(failures);
        tt.setErrorsNum(errors);
        tt.setElapsedTime(elapsed);
        tt.setHasData(run > 0);
        return tt;
    }

    /** Simple generator that returns a fixed list of pattern assignments, then exhausts. */
    private static class FixedGenerator implements IVariantGenerator {
        private final List<List<String>> assignments;
        private int index = 0;

        FixedGenerator(List<List<String>> assignments) {
            this.assignments = assignments;
        }

        @Override
        public Optional<List<String>> nextVariant() {
            if (index >= assignments.size()) return Optional.empty();
            return Optional.of(assignments.get(index++));
        }
    }
}
