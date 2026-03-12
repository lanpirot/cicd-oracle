package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.util.RepositoryStatus;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Result object containing metadata from the merge conflict collection process.
 * Provides detailed statistics about what was processed and why repositories were rejected.
 */
@Getter
@Builder
@ToString
public class CollectionResult {
    /**
     * The final status of the collection process
     */
    private final RepositoryStatus status;

    /**
     * Repository name
     */
    private final String repoName;

    /**
     * Repository URL
     */
    private final String repoUrl;

    /**
     * Whether the repository is a Maven project
     */
    private final boolean isMaven;

    /**
     * Total number of merge commits found in the repository
     */
    private final int totalMerges;

    /**
     * Number of merges that had any conflicts
     */
    private final int mergesWithConflicts;

    /**
     * Number of merges that had Java file conflicts
     */
    private final int mergesWithJavaConflicts;

    /**
     * Number of merges that were successfully processed and added to the dataset
     * (compilable, testable, within timeout limits)
     */
    private final int successfulMerges;

    /**
     * Number of merges that had no tests
     */
    private final int mergesWithNoTests;

    /**
     * Number of merges that timed out during compilation
     */
    private final int mergesTimedOut;

    /**
     * Human-readable explanation of the result
     */
    private final String message;

    /**
     * Get a summary line suitable for logging
     */
    public String getSummary() {
        return String.format("%s: %s (processed %d/%d merges with Java conflicts)",
                repoName, status, successfulMerges, mergesWithJavaConflicts);
    }

    /**
     * Get detailed statistics
     */
    public String getDetailedStats() {
        return String.format("""
                Repository: %s
                URL: %s
                Maven: %s
                Status: %s
                Total merges: %d
                Merges with conflicts: %d
                Merges with Java conflicts: %d
                Successfully processed: %d
                Merges with no tests: %d
                Merges timed out: %d
                Message: %s""",
                repoName, repoUrl, isMaven, status,
                totalMerges, mergesWithConflicts, mergesWithJavaConflicts,
                successfulMerges, mergesWithNoTests, mergesTimedOut, message);
    }
}