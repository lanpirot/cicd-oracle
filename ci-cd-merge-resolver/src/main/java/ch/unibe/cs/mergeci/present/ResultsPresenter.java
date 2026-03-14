package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.conflict.MergeStatistics;
import ch.unibe.cs.mergeci.experiment.AllMergesJSON;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
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

        for (File file : dir.toFile().listFiles()) {
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

            int baselineSuccesses = analyzer.countSuccessfulTests(merge.getTestResults());

            for (MergeOutputJSON.Variant variant : merge.getVariantsExecution().getVariants()) {
                int variantSuccesses = analyzer.countSuccessfulTests(variant.getTestResults());

                if (variantSuccesses != baselineSuccesses) {
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
