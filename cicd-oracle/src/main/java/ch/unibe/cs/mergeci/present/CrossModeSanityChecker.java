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
 * Remaining deviations indicate flaky tests (test mismatches) or infrastructure bugs
 * (compilation mismatches).
 */
public class CrossModeSanityChecker {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public record SanityCheckResult(int variantsCompared, int compilationMismatches, int testMismatches) {}

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
        if (byMode.size() < 2) return new SanityCheckResult(0, 0, 0);

        Set<String> allMergeKeys = new LinkedHashSet<>();
        byMode.values().forEach(m -> allMergeKeys.addAll(m.keySet()));

        int variantsCompared = 0;
        int compilationMismatches = 0;
        int testMismatches = 0;
        List<String> compilationDetails = new ArrayList<>();
        List<String> testDetails = new ArrayList<>();

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

                // Check compilation status: all modes must agree
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
                for (int i = 1; i < comparable.size(); i++) {
                    TestTotal tr = comparable.get(i).getValue().getTestResults();
                    if (refTr == null || tr == null) continue;
                    if (refTr.getRunNum() != tr.getRunNum()
                            || refTr.getFailuresNum() != tr.getFailuresNum()
                            || refTr.getErrorsNum() != tr.getErrorsNum()
                            || refTr.getSkippedNum() != tr.getSkippedNum()) {
                        testMismatch = true;
                        if (testDetails.size() < 10) {
                            testDetails.add(String.format(
                                    "  %s variant %d: run=%d/%d fail=%d/%d err=%d/%d skip=%d/%d (%s vs %s)",
                                    mergeKey, idx,
                                    refTr.getRunNum(), tr.getRunNum(),
                                    refTr.getFailuresNum(), tr.getFailuresNum(),
                                    refTr.getErrorsNum(), tr.getErrorsNum(),
                                    refTr.getSkippedNum(), tr.getSkippedNum(),
                                    comparable.get(0).getKey(), comparable.get(i).getKey()));
                        }
                    }
                }
                if (testMismatch) testMismatches++;
            }
        }

        printReport(byMode.keySet(), variantsCompared, compilationMismatches, testMismatches,
                compilationDetails, testDetails);
        return new SanityCheckResult(variantsCompared, compilationMismatches, testMismatches);
    }

    private static void printReport(Set<String> modes, int variantsCompared,
                                    int compilationMismatches, int testMismatches,
                                    List<String> compilationDetails, List<String> testDetails) {
        System.out.println("\n" + "═".repeat(80));
        System.out.println("  CROSS-MODE SANITY CHECK");
        System.out.println("═".repeat(80));
        System.out.printf("  Modes compared:              %s%n", String.join(", ", modes));
        System.out.printf("  Variant positions compared:  %d%n", variantsCompared);

        if (compilationMismatches == 0) {
            System.out.println("  Compilation outcomes:        OK (all consistent)");
        } else {
            System.out.printf("  WARNING — Compilation mismatches: %d/%d (%.1f%%)%n",
                    compilationMismatches, variantsCompared,
                    100.0 * compilationMismatches / variantsCompared);
            compilationDetails.forEach(System.out::println);
        }

        if (testMismatches == 0) {
            System.out.println("  Test counts:                 OK (all consistent)");
        } else {
            System.out.printf("  Test count mismatches (possibly flaky): %d/%d (%.1f%%)%n",
                    testMismatches, variantsCompared,
                    100.0 * testMismatches / variantsCompared);
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
