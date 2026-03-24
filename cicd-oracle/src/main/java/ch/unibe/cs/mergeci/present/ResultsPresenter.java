package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.conflict.MergeStatistics;
import ch.unibe.cs.mergeci.experiment.AllMergesJSON;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.util.Utility;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Getter
public class ResultsPresenter {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MergeStatistics statistics;
    private final Path dir;

    public ResultsPresenter(Utility.Experiments ex) {
        this(AppConfig.VARIANT_EXPERIMENT_DIR.resolve(ex.getName()));
    }

    public ResultsPresenter(Path dir) {
        this.dir = dir;
        List<MergeOutputJSON> merges = loadMerges(dir);
        this.statistics = MergeStatistics.from(merges);
    }

    /**
     * Load all merge results from JSON files in the directory.
     */
    private List<MergeOutputJSON> loadMerges(Path dir) {
        List<MergeOutputJSON> allMerges = new ArrayList<>();

        File[] files = dir.toFile().listFiles();
        if (files == null) return allMerges;
        for (File file : files) {
            try {
                AllMergesJSON allMergesJSON = objectMapper.readValue(file, AllMergesJSON.class);
                allMerges.addAll(allMergesJSON.getMerges());
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return allMerges;
    }

    /**
     * Present comprehensive analysis results to console.
     * Delegates to StatisticsReporter for all presentation logic.
     */
    public void presentFullResults() {
        System.out.println("================================================================================");
        System.out.println("CI/CD Merge Resolver - Presentation Phase");
        System.out.println("================================================================================\n");

        String experimentName = dir.getFileName().toString();
        StatisticsReporter reporter = new StatisticsReporter(statistics, experimentName);
        reporter.presentFullResults();
    }

    /**
     * Get all merges (for backward compatibility).
     */
    public List<MergeOutputJSON> getMerges() {
        return statistics.getAllMerges();
    }

    public int countMultiModulesProjects() {
        return statistics.getMultiModuleCount();
    }

    /**
     * Filter merges where variants have different test results than the original.
     * This identifies "impact" merges where resolution choice affects test outcomes.
     *
     * @deprecated Use MergeStatistics.getImpactMerges() instead
     */
    public List<MergeOutputJSON> filterOutNoImpactMerges(List<MergeOutputJSON> merges) {
        VariantResolutionAnalyzer analyzer = new VariantResolutionAnalyzer();
        List<MergeOutputJSON> impactMerges = new ArrayList<>();

        for (MergeOutputJSON merge : merges) {
            if (merge.getNumConflictChunks() == 0) continue;

            java.util.Optional<VariantScore> baselineScore =
                    VariantScore.of(merge.getCompilationResult(), merge.getTestResults());
            if (baselineScore.isEmpty()) continue;

            for (MergeOutputJSON.Variant variant : merge.getVariants()) {
                java.util.Optional<VariantScore> vs =
                        VariantScore.of(variant.getCompilationResult(), variant.getTestResults());
                if (vs.isEmpty()) continue; // timeout — excluded

                if (!vs.get().equals(baselineScore.get())) {
                    impactMerges.add(merge);
                    break;
                }
            }
        }

        return impactMerges;
    }

    public static List<MergeOutputJSON> getMultiModuleProjects(List<MergeOutputJSON> merges) {
        return merges.stream().filter(MergeOutputJSON::getIsMultiModule).toList();
    }

    public static List<MergeOutputJSON> getSingleModuleProjects(List<MergeOutputJSON> merges) {
        return merges.stream().filter(x -> !x.getIsMultiModule()).toList();
    }

}
