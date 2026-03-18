package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.present.VariantScore;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Aggregated statistics and categorizations of merge analysis results.
 * This class pre-computes various subsets of merges for efficient analysis.
 */
@Getter
public class MergeStatistics {
    private final List<MergeOutputJSON> allMerges;
    private final List<MergeOutputJSON> impactMerges;
    private final List<MergeOutputJSON> noImpactMerges;
    private final MergeSubsets singleModule;
    private final MergeSubsets multiModule;
    private final int multiModuleCount;

    private MergeStatistics(
            List<MergeOutputJSON> allMerges,
            List<MergeOutputJSON> impactMerges,
            List<MergeOutputJSON> noImpactMerges,
            MergeSubsets singleModule,
            MergeSubsets multiModule,
            int multiModuleCount) {
        this.allMerges = allMerges;
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
        List<MergeOutputJSON> impactMerges = filterImpactMerges(merges);
        List<MergeOutputJSON> noImpactMerges = merges.stream()
                .filter(m -> !impactMerges.contains(m))
                .toList();

        int multiModuleCount = (int) merges.stream()
                .filter(MergeOutputJSON::getIsMultiModule)
                .count();

        MergeSubsets singleModule = buildSubsets(
                filterSingleModule(impactMerges),
                filterSingleModule(noImpactMerges)
        );

        MergeSubsets multiModule = buildSubsets(
                filterMultiModule(impactMerges),
                filterMultiModule(noImpactMerges)
        );

        return new MergeStatistics(
                merges,
                impactMerges,
                noImpactMerges,
                singleModule,
                multiModule,
                multiModuleCount
        );
    }

    private static MergeSubsets buildSubsets(
            List<MergeOutputJSON> impact,
            List<MergeOutputJSON> noImpact) {
        return new MergeSubsets(
                impact,
                noImpact,
                addCoverageInfo(impact),
                addCoverageInfo(noImpact)
        );
    }

    private static List<MergeOutputJSON> filterImpactMerges(List<MergeOutputJSON> merges) {
        List<MergeOutputJSON> result = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            if (merge.getNumConflictChunks() == 0) continue;

            Optional<VariantScore> baselineScore =
                    VariantScore.of(merge.getCompilationResult(), merge.getTestResults());
            if (baselineScore.isEmpty()) continue; // no scoreable baseline

            for (MergeOutputJSON.Variant variant : merge.getVariants()) {
                if (variant.getVariantIndex() == 0) continue;
                Optional<VariantScore> vs =
                        VariantScore.of(variant.getCompilationResult(), variant.getTestResults());
                if (vs.isEmpty()) continue; // timeout — excluded from comparison

                if (!vs.get().equals(baselineScore.get())) {
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

    /**
         * Categorized subsets of merges (single-module or multi-module).
         */
        public record MergeSubsets(List<MergeOutputJSON> impact, List<MergeOutputJSON> noImpact,
                                   List<MergeOutputJSON> impactWithCoverage, List<MergeOutputJSON> noImpactWithCoverage) {
    }
}
