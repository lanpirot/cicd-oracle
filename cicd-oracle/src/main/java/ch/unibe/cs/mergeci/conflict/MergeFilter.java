package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.model.MergeInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Filters merge commits: excludes training-set merges to prevent data leakage,
 * and provides counting utilities for reporting.
 */
public class MergeFilter {

    /**
     * Merge IDs present in the pattern-learning training set.
     * Loaded once from the classpath resource on first use.
     */
    private static final Set<String> TRAINING_MERGE_IDS = loadTrainingMergeIds();

    private static Set<String> loadTrainingMergeIds() {
        Set<String> ids = new HashSet<>();
        InputStream is = MergeFilter.class.getClassLoader()
                .getResourceAsStream("pattern-heuristics/training_mergeIDs.csv");
        if (is == null) {
            System.err.println("Warning: training_mergeIDs.csv not found on classpath — no training-set filtering applied");
            return ids;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is))) {
            boolean first = true;
            String line;
            while ((line = reader.readLine()) != null) {
                if (first) { first = false; continue; } // skip header row
                line = line.trim();
                if (!line.isEmpty()) ids.add(line);
            }
        } catch (IOException e) {
            System.err.println("Warning: Failed to load training_mergeIDs.csv: " + e.getMessage());
        }
        System.out.printf("Loaded %d training merge IDs to exclude from dataset collection.%n", ids.size());
        return ids;
    }

    /**
     * Returns true if this merge commit was part of the pattern-learning training set
     * and should be excluded from the experiment dataset.
     */
    public boolean isTrainingMerge(MergeInfo merge) {
        return TRAINING_MERGE_IDS.contains(merge.getResultedMergeCommit().getName());
    }

    /**
     * Count the number of merges that have at least one Java file conflict.
     * Used for reporting only; does not gate processing.
     */
    public int countJavaConflicts(List<MergeInfo> merges) {
        return (int) merges.stream()
                .filter(m -> m.getConflictingFiles().keySet().stream()
                        .anyMatch(f -> f.endsWith(AppConfig.JAVA)))
                .count();
    }
}
