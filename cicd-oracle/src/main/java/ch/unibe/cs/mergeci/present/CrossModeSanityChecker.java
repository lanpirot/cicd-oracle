package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.Utility;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Verifies that identical variants produce identical build outcomes across all experiment modes.
 * Cache and parallelism optimizations must not change compilation status or test counts.
 * Timed-out variants are excluded — their outcome is undefined.
 * <p>
 * Compilation mismatches have zero tolerance — any mismatch is a hard failure.
 * Test mismatches are allowed up to {@link #MAX_TEST_MISMATCH_RATE} (1%) to account for flaky
 * tests; above that threshold a warning is emitted and the result is flagged.
 */
public class CrossModeSanityChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Maximum fraction of variant positions with test-count mismatches before flagging. */
    static final double MAX_TEST_MISMATCH_RATE = 0.01;

    /**
     * @param variantsCompared        number of variant positions compared across ≥2 modes
     * @param compilationMismatches   positions where compilation status differed (zero tolerance)
     * @param testMismatches          positions where test counts differed
     * @param maxTestDeviation        worst-case deviation: max |Δrun|+|Δfail|+|Δerr| as fraction of total tests
     * @param medianTestDeviation     median deviation across all mismatched positions (0 if none)
     * @param passed                  true iff compilationMismatches == 0 AND test mismatch rate ≤ threshold
     */
    public record SanityCheckResult(int variantsCompared, int compilationMismatches, int testMismatches,
                                    double maxTestDeviation, double medianTestDeviation, boolean passed) {}

    /**
     * Loads JSON results from each mode subdirectory under {@code experimentDir} and runs the
     * cross-mode consistency check.
     */
    public static SanityCheckResult check(Path experimentDir, List<Utility.Experiments> modes) {
        Map<String, Map<String, MergeOutputJSON>> byMode = new LinkedHashMap<>();
        for (Utility.Experiments ex : modes) {
            Map<String, MergeOutputJSON> merges = loadMerges(experimentDir.resolve(ex.getName()));
            if (!merges.isEmpty()) byMode.put(ex.getName(), merges);
        }
        return checkInMemory(byMode);
    }

    /**
     * Core comparison logic — package-private so unit tests can supply pre-built in-memory data.
     *
     * @param byMode mode-name → (mergeKey → MergeOutputJSON)
     */
    static SanityCheckResult checkInMemory(Map<String, Map<String, MergeOutputJSON>> byMode) {
        if (byMode.size() < 2) return new SanityCheckResult(0, 0, 0, 0.0, 0.0, true);

        Set<String> allMergeKeys = new LinkedHashSet<>();
        byMode.values().forEach(m -> allMergeKeys.addAll(m.keySet()));

        int variantsCompared = 0;
        int compilationMismatches = 0;
        int testMismatches = 0;
        List<String> compilationDetails = new ArrayList<>();
        List<String> testDetails = new ArrayList<>();
        List<Double> testDeviations = new ArrayList<>();

        for (String mergeKey : allMergeKeys) {
            List<Map.Entry<String, MergeOutputJSON>> presentModes = new ArrayList<>();
            for (var entry : byMode.entrySet()) {
                MergeOutputJSON m = entry.getValue().get(mergeKey);
                if (m != null) presentModes.add(Map.entry(entry.getKey(), m));
            }
            if (presentModes.size() < 2) continue;

            Set<Integer> variantIndices = new LinkedHashSet<>();
            for (var me : presentModes) {
                if (me.getValue().getVariants() != null) {
                    me.getValue().getVariants().stream()
                            .map(MergeOutputJSON.Variant::getVariantIndex)
                            .forEach(variantIndices::add);
                }
            }

            for (int idx : variantIndices) {
                // Collect non-timed-out results for this variant across all modes
                List<Map.Entry<String, MergeOutputJSON.Variant>> comparable = new ArrayList<>();
                for (var me : presentModes) {
                    MergeOutputJSON.Variant v = findVariant(me.getValue(), idx);
                    if (v == null || v.isTimedOut()) continue;
                    comparable.add(Map.entry(me.getKey(), v));
                }
                if (comparable.size() < 2) continue;

                variantsCompared++;
                MergeOutputJSON.Variant ref = comparable.get(0).getValue();

                // Check compilation status: all modes must agree (zero tolerance)
                CompilationResult.Status refStatus = ref.getCompilationResult() != null
                        ? ref.getCompilationResult().getBuildStatus() : null;
                boolean compilationMismatch = false;
                for (int i = 1; i < comparable.size(); i++) {
                    var cr = comparable.get(i).getValue().getCompilationResult();
                    CompilationResult.Status s = cr != null ? cr.getBuildStatus() : null;
                    if (!Objects.equals(refStatus, s)) {
                        compilationMismatch = true;
                        if (compilationDetails.size() < 10) {
                            compilationDetails.add(String.format("  %s variant %d: %s=%s vs %s=%s",
                                    mergeKey, idx,
                                    comparable.get(0).getKey(), refStatus,
                                    comparable.get(i).getKey(), s));
                        }
                    }
                }
                if (compilationMismatch) compilationMismatches++;

                // Check test counts: all modes must agree (where data is available)
                TestTotal refTr = ref.getTestResults();
                boolean testMismatch = false;
                double worstDeviation = 0.0;
                for (int i = 1; i < comparable.size(); i++) {
                    TestTotal tr = comparable.get(i).getValue().getTestResults();
                    if (refTr == null || tr == null) continue;
                    int deltaRun  = Math.abs(refTr.getRunNum()      - tr.getRunNum());
                    int deltaFail = Math.abs(refTr.getFailuresNum() - tr.getFailuresNum());
                    int deltaErr  = Math.abs(refTr.getErrorsNum()   - tr.getErrorsNum());
                    if (deltaRun + deltaFail + deltaErr > 0) {
                        testMismatch = true;
                        int maxTests = Math.max(refTr.getRunNum(), tr.getRunNum());
                        double deviation = maxTests > 0
                                ? (double) (deltaRun + deltaFail + deltaErr) / maxTests
                                : 1.0;
                        worstDeviation = Math.max(worstDeviation, deviation);
                        if (testDetails.size() < 10) {
                            testDetails.add(String.format(
                                    "  %s variant %d: run=%d/%d fail=%d/%d err=%d/%d skip=%d/%d (dev=%.1f%%, %s vs %s)",
                                    mergeKey, idx,
                                    refTr.getRunNum(), tr.getRunNum(),
                                    refTr.getFailuresNum(), tr.getFailuresNum(),
                                    refTr.getErrorsNum(), tr.getErrorsNum(),
                                    refTr.getSkippedNum(), tr.getSkippedNum(),
                                    deviation * 100,
                                    comparable.get(0).getKey(), comparable.get(i).getKey()));
                        }
                    }
                }
                if (testMismatch) {
                    testMismatches++;
                    testDeviations.add(worstDeviation);
                }
            }
        }

        double maxDev = testDeviations.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        double medianDev = median(testDeviations);
        double testMismatchRate = variantsCompared > 0 ? (double) testMismatches / variantsCompared : 0.0;
        boolean passed = compilationMismatches == 0 && testMismatchRate <= MAX_TEST_MISMATCH_RATE;

        printReport(byMode.keySet(), variantsCompared, compilationMismatches, testMismatches,
                compilationDetails, testDetails, maxDev, medianDev, passed);
        return new SanityCheckResult(variantsCompared, compilationMismatches, testMismatches,
                maxDev, medianDev, passed);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0.0;
        List<Double> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int n = sorted.size();
        return n % 2 == 0
                ? (sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0
                : sorted.get(n / 2);
    }

    private static void printReport(Set<String> modes, int variantsCompared,
                                    int compilationMismatches, int testMismatches,
                                    List<String> compilationDetails, List<String> testDetails,
                                    double maxTestDeviation, double medianTestDeviation,
                                    boolean passed) {
        System.out.println("\n" + "═".repeat(80));
        System.out.printf("  CROSS-MODE SANITY CHECK  %s%n", passed ? "PASSED" : "FAILED");
        System.out.println("═".repeat(80));
        System.out.printf("  Modes compared:              %s%n", String.join(", ", modes));
        System.out.printf("  Variant positions compared:  %d%n", variantsCompared);

        if (compilationMismatches == 0) {
            System.out.println("  Compilation outcomes:        OK (all consistent)");
        } else {
            System.out.printf("  FAIL — Compilation mismatches: %d/%d (%.1f%%) — zero tolerance%n",
                    compilationMismatches, variantsCompared,
                    100.0 * compilationMismatches / variantsCompared);
            compilationDetails.forEach(System.out::println);
        }

        if (testMismatches == 0) {
            System.out.println("  Test counts:                 OK (all consistent)");
        } else {
            double rate = 100.0 * testMismatches / variantsCompared;
            String verdict = rate <= MAX_TEST_MISMATCH_RATE * 100 ? "OK (within threshold)" : "FAIL";
            System.out.printf("  Test count mismatches: %s — %d/%d (%.2f%%, threshold %.0f%%)%n",
                    verdict, testMismatches, variantsCompared, rate, MAX_TEST_MISMATCH_RATE * 100);
            System.out.printf("  Deviation magnitude:         median=%.1f%%, max=%.1f%% of total tests%n",
                    medianTestDeviation * 100, maxTestDeviation * 100);
            testDetails.forEach(System.out::println);
        }
        System.out.println("═".repeat(80) + "\n");
    }

    private static MergeOutputJSON.Variant findVariant(MergeOutputJSON merge, int idx) {
        if (merge.getVariants() == null) return null;
        return merge.getVariants().stream()
                .filter(v -> v.getVariantIndex() == idx)
                .findFirst().orElse(null);
    }

    private static Map<String, MergeOutputJSON> loadMerges(Path modeDir) {
        Map<String, MergeOutputJSON> map = new LinkedHashMap<>();
        File[] files = modeDir.toFile().listFiles();
        if (files == null) return map;
        for (File file : files) {
            if (!file.getName().endsWith(AppConfig.JSON)) continue;
            try {
                MergeOutputJSON m = MAPPER.readValue(file, MergeOutputJSON.class);
                map.put(m.getProjectName() + "/" + m.getMergeCommit(), m);
            } catch (IOException e) {
                System.err.println("Warning: could not load " + file.getName() + ": " + e.getMessage());
            }
        }
        return map;
    }
}
