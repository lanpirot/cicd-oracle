package ch.unibe.cs.mergeci.experimentSetup.mergeCollection;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.model.MergeInfo;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Filters and validates merge commits based on conflict criteria.
 * Focuses on identifying merges with Java file conflicts.
 */
public class MergeFilter {

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
     * Filter a list of merges to only those with Java file conflicts.
     *
     * @param merges List of all merges
     * @return List of merges with Java file conflicts
     */
    public List<MergeInfo> filterJavaConflicts(List<MergeInfo> merges) {
        return merges.stream()
                .filter(this::hasJavaConflicts)
                .collect(Collectors.toList());
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
