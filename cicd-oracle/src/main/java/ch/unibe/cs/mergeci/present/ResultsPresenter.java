package ch.unibe.cs.mergeci.present;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.conflict.MergeStatistics;
import ch.unibe.cs.mergeci.experiment.MergeOutputJSON;
import ch.unibe.cs.mergeci.util.Utility;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, MergeOutputJSON> baselineLookup = loadBaselineLookup(dir);
        this.statistics = MergeStatistics.from(merges, baselineLookup);
    }

    /**
     * Load all merge results from JSON files in the directory.
     */
    private List<MergeOutputJSON> loadMerges(Path dir) {
        List<MergeOutputJSON> allMerges = new ArrayList<>();

        File[] files = dir.toFile().listFiles();
        if (files == null) return allMerges;
        for (File file : files) {
            if (!file.getName().endsWith(AppConfig.JSON)) continue;
            try {
                allMerges.add(objectMapper.readValue(file, MergeOutputJSON.class));
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        return allMerges;
    }

    /**
     * For non-baseline modes, build a map of {@code mergeCommit → human-baseline merge}
     * from the sibling {@code human_baseline} directory. Non-baseline JSONs only contain
     * variants 1+; without this lookup, every merge would be skipped from impact analysis
     * for lack of a scoreable baseline. Downstream analyzers (resolution, ranking, time)
     * also consult the lookup as a fallback for variant-0 data.
     */
    private Map<String, MergeOutputJSON> loadBaselineLookup(Path modeDir) {
        String modeName = modeDir.getFileName().toString();
        String baselineName = Utility.Experiments.human_baseline.getName();
        if (modeName.equals(baselineName)) {
            return Map.of();
        }
        Path baselineDir = modeDir.resolveSibling(baselineName);
        File[] files = baselineDir.toFile().listFiles();
        if (files == null) return Map.of();

        Map<String, MergeOutputJSON> result = new HashMap<>();
        for (File file : files) {
            if (!file.getName().endsWith(AppConfig.JSON)) continue;
            try {
                MergeOutputJSON merge = objectMapper.readValue(file, MergeOutputJSON.class);
                String key = merge.getMergeCommit();
                if (key != null) result.put(key, merge);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
        return result;
    }

    /**
     * Present comprehensive analysis results to console.
     * Delegates to StatisticsReporter for all presentation logic.
     * The banner is printed once by the caller, not here.
     */
    public void presentFullResults() {
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

    public static List<MergeOutputJSON> getMultiModuleProjects(List<MergeOutputJSON> merges) {
        return merges.stream().filter(MergeOutputJSON::getIsMultiModule).toList();
    }

    public static List<MergeOutputJSON> getSingleModuleProjects(List<MergeOutputJSON> merges) {
        return merges.stream().filter(x -> !x.getIsMultiModule()).toList();
    }

}
