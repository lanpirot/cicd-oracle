package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.present.VariantScore;
import lombok.Getter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
    /** Per-merge baseline records loaded from {@code human_baseline}, keyed by merge commit.
     *  Empty when this MergeStatistics was built for the human_baseline mode itself. */
    private final Map<String, MergeOutputJSON> baselineLookup;

    private MergeStatistics(
            List<MergeOutputJSON> allMerges,
            List<MergeOutputJSON> impactMerges,
            List<MergeOutputJSON> noImpactMerges,
            MergeSubsets singleModule,
            MergeSubsets multiModule,
            int multiModuleCount,
            Map<String, MergeOutputJSON> baselineLookup) {
        this.allMerges = allMerges;
        this.impactMerges = impactMerges;
        this.noImpactMerges = noImpactMerges;
        this.singleModule = singleModule;
        this.multiModule = multiModule;
        this.multiModuleCount = multiModuleCount;
        this.baselineLookup = baselineLookup;
    }

    /**
     * Create statistics from a list of merges.
     * Pre-computes all subsets and categorizations.
     *
     * @param merges All merge results to analyze
     * @return Computed statistics
     */
    public static MergeStatistics from(List<MergeOutputJSON> merges) {
        return from(merges, Map.of());
    }

    /**
     * Create statistics from a list of merges with an external baseline lookup.
     * Non-baseline mode JSONs do not include variant 0, so the baseline must be provided
     * here (typically the human_baseline merge records keyed by
     * {@link MergeOutputJSON#getMergeCommit()}). Used as a fallback when the merge's
     * own variant-0 data is unavailable, and also exposed via {@link #getBaselineLookup()}
     * for downstream analyzers (resolution, ranking, time).
     *
     * @param merges          All merge results to analyze
     * @param baselineLookup  Map of mergeCommit → human-baseline merge record
     * @return Computed statistics
     */
    public static MergeStatistics from(List<MergeOutputJSON> merges,
                                       Map<String, MergeOutputJSON> baselineLookup) {
        List<MergeOutputJSON> impactMerges = filterImpactMerges(merges, baselineLookup);
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
                multiModuleCount,
                baselineLookup
        );
    }

    private static MergeSubsets buildSubsets(
            List<MergeOutputJSON> impact,
            List<MergeOutputJSON> noImpact) {
        return new MergeSubsets(impact, noImpact);
    }

    private static List<MergeOutputJSON> filterImpactMerges(List<MergeOutputJSON> merges,
                                                            Map<String, MergeOutputJSON> baselineLookup) {
        List<MergeOutputJSON> result = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            if (merge.getNumConflictChunks() == 0) continue;

            Optional<VariantScore> baselineScore = scoreFor(merge, baselineLookup);
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

    /**
     * Resolve the baseline {@link VariantScore} for a merge, preferring inline variant-0
     * data and falling back to the cross-mode lookup when the merge's JSON is non-baseline.
     */
    public static Optional<VariantScore> scoreFor(MergeOutputJSON merge,
                                                  Map<String, MergeOutputJSON> baselineLookup) {
        Optional<VariantScore> inline =
                VariantScore.of(merge.getCompilationResult(), merge.getTestResults());
        if (inline.isPresent()) return inline;
        MergeOutputJSON baseline = baselineLookup.get(merge.getMergeCommit());
        if (baseline == null) return Optional.empty();
        return VariantScore.of(baseline.getCompilationResult(), baseline.getTestResults());
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

    /**
     * Categorized subsets of merges (single-module or multi-module).
     */
    public record MergeSubsets(List<MergeOutputJSON> impact, List<MergeOutputJSON> noImpact) {
    }
}
