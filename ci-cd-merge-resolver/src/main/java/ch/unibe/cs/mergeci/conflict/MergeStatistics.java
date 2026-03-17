package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Aggregated statistics and categorizations of merge analysis results.
 * This class pre-computes various subsets of merges for efficient analysis.
 */
@Getter
public class MergeStatistics {
    private final List<MergeOutputJSON> allMerges;
    private final List<MergeOutputJSON> testImpactMerges;
    private final List<MergeOutputJSON> buildImpactMerges;
    private final List<MergeOutputJSON> impactMerges;
    private final List<MergeOutputJSON> noImpactMerges;
    private final MergeSubsets singleModule;
    private final MergeSubsets multiModule;
    private final int multiModuleCount;

    private MergeStatistics(
            List<MergeOutputJSON> allMerges,
            List<MergeOutputJSON> testImpactMerges,
            List<MergeOutputJSON> buildImpactMerges,
            List<MergeOutputJSON> impactMerges,
            List<MergeOutputJSON> noImpactMerges,
            MergeSubsets singleModule,
            MergeSubsets multiModule,
            int multiModuleCount) {
        this.allMerges = allMerges;
        this.testImpactMerges = testImpactMerges;
        this.buildImpactMerges = buildImpactMerges;
        this.impactMerges = impactMerges;
        this.noImpactMerges = noImpactMerges;
        this.singleModule = singleModule;
        this.multiModule = multiModule;
        this.multiModuleCount = multiModuleCount;
    }

    /**
     * Create statistics from a list of merges.
     * Pre-computes all subsets and categorizations.
     *
     * @param merges All merge results to analyze
     * @return Computed statistics
     */
    public static MergeStatistics from(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> testImpactMerges = filterTestImpactMerges(merges);
        List<MergeOutputJSON> buildImpactMerges = filterBuildImpactMerges(merges);
        List<MergeOutputJSON> impactMerges = merges.stream()
                .filter(m -> testImpactMerges.contains(m) || buildImpactMerges.contains(m))
                .toList();
        List<MergeOutputJSON> noImpactMerges = merges.stream()
                .filter(m -> !impactMerges.contains(m))
                .toList();

        int multiModuleCount = (int) merges.stream()
                .filter(MergeOutputJSON::getIsMultiModule)
                .count();

        MergeSubsets singleModule = buildSubsets(
                filterSingleModule(testImpactMerges),
                filterSingleModule(buildImpactMerges),
                filterSingleModule(impactMerges),
                filterSingleModule(noImpactMerges)
        );

        MergeSubsets multiModule = buildSubsets(
                filterMultiModule(testImpactMerges),
                filterMultiModule(buildImpactMerges),
                filterMultiModule(impactMerges),
                filterMultiModule(noImpactMerges)
        );

        return new MergeStatistics(
                merges,
                testImpactMerges,
                buildImpactMerges,
                impactMerges,
                noImpactMerges,
                singleModule,
                multiModule,
                multiModuleCount
        );
    }

    private static MergeSubsets buildSubsets(
            List<MergeOutputJSON> testImpact,
            List<MergeOutputJSON> buildImpact,
            List<MergeOutputJSON> impact,
            List<MergeOutputJSON> noImpact) {
        return new MergeSubsets(
                testImpact,
                buildImpact,
                impact,
                noImpact,
                addCoverageInfo(impact),
                addCoverageInfo(noImpact)
        );
    }

    private static List<MergeOutputJSON> filterTestImpactMerges(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> result = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            if (merge.getNumConflictChunks() == 0) continue;

            int baselineSuccessTests = numberOfSuccessTests(merge.getTestResults());

            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                int variantSuccessTests = numberOfSuccessTests(variant.getTestResults());

                if (variantSuccessTests != baselineSuccessTests) {
                    result.add(merge);
                    break;
                }
            }
        }

        return result;
    }

    private static List<MergeOutputJSON> filterBuildImpactMerges(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> result = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            if (merge.getNumConflictChunks() == 0) continue;

            CompilationResult baseline = merge.getCompilationResult();
            if (baseline == null) continue;

            CompilationResult.Status baselineStatus = baseline.getBuildStatus();
            int baselineSuccessModules = baseline.getNumberOfSuccessfulModules();

            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                if ("human_baseline".equals(variant.getVariantName())) continue;
                CompilationResult cr = variant.getCompilationResult();
                if (cr == null) continue;

                if (cr.getBuildStatus() != baselineStatus
                        || cr.getNumberOfSuccessfulModules() != baselineSuccessModules) {
                    result.add(merge);
                    break;
                }
            }
        }

        return result;
    }

    private static List<MergeOutputJSON> filterSingleModule(List<MergeOutputJSON> merges) {
        return merges.stream()
                .filter(x -> !x.getIsMultiModule())
                .collect(Collectors.toList());
    }

    private static List<MergeOutputJSON> filterMultiModule(List<MergeOutputJSON> merges) {
        return merges.stream()
                .filter(MergeOutputJSON::getIsMultiModule)
                .collect(Collectors.toList());
    }

    private static List<MergeOutputJSON> addCoverageInfo(List<MergeOutputJSON> merges) {
        return merges.stream()
                .peek(x -> {
                    if (x.getCoverage() == null) {
                        x.setCoverage(new JacocoReportFinder.CoverageDTO(Float.NaN, Float.NaN));
                    }
                })
                .collect(Collectors.toList());
    }

    private static int numberOfSuccessTests(TestTotal testTotal) {
        return testTotal.getRunNum() - testTotal.getErrorsNum() - testTotal.getFailuresNum();
    }

    /**
     * Categorized subsets of merges (single-module or multi-module).
     */
    @Getter
    public static class MergeSubsets {
        private final List<MergeOutputJSON> testImpact;
        private final List<MergeOutputJSON> buildImpact;
        private final List<MergeOutputJSON> impact;
        private final List<MergeOutputJSON> noImpact;
        private final List<MergeOutputJSON> impactWithCoverage;
        private final List<MergeOutputJSON> noImpactWithCoverage;

        public MergeSubsets(
                List<MergeOutputJSON> testImpact,
                List<MergeOutputJSON> buildImpact,
                List<MergeOutputJSON> impact,
                List<MergeOutputJSON> noImpact,
                List<MergeOutputJSON> impactWithCoverage,
                List<MergeOutputJSON> noImpactWithCoverage) {
            this.testImpact = testImpact;
            this.buildImpact = buildImpact;
            this.impact = impact;
            this.noImpact = noImpact;
            this.impactWithCoverage = impactWithCoverage;
            this.noImpactWithCoverage = noImpactWithCoverage;
        }
    }
}
