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
 * Filters and validates merge commits based on conflict criteria.
 * Focuses on identifying merges with Java file conflicts.
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
     * Check if a merge has conflicts in Java files.
     *
     * @param merge Merge information to check
     * @return true if the merge has Java file conflicts
     */
    public boolean hasJavaConflicts(MergeInfo merge) {
        return merge.getConflictingFiles()
                .keySet()
                .stream()
                .anyMatch(file -> file.endsWith(AppConfig.JAVA));
    }

    /**
     * Count the number of merges with Java file conflicts.
     *
     * @param merges List of merges to count
     * @return Number of merges with Java conflicts
     */
    public int countJavaConflicts(List<MergeInfo> merges) {
        return (int) merges.stream()
                .filter(this::hasJavaConflicts)
                .count();
    }
}
