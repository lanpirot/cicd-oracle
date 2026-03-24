package ch.unibe.cs.mergeci.util;

/**
 * Enum representing the status of a repository in the processing pipeline.
 */
public enum RepositoryStatus {
    /**
     * Repository has not been processed yet and has not been cloned
     */
    NOT_PROCESSED,

    /**
     * Repository has been cloned but not yet processed
     */
    NOT_PROCESSED_BUT_CLONED,

    /**
     * Repository is currently being processed
     */
    PROCESSING,
    
    /**
     * Repository was successfully processed and is valid
     */
    SUCCESS,
    
    /**
     * Repository was rejected because it's not a Maven project (missing pom.xml)
     */
    REJECTED_NO_POM,
    
    /**
     * Repository was rejected because cloning failed
     */
    REJECTED_CLONE_FAILED,
    
    /**
     * Repository was rejected because it has no merge conflicts at all
     */
    REJECTED_NO_CONFLICTS,

    /**
     * Repository was rejected because it has no tests
     */
    REJECTED_NO_TESTS,

    /**
     * Repository was rejected because all qualifying merges timed out during the baseline build
     */
    REJECTED_TIMEOUT,

    /**
     * Repository was rejected because all qualifying merges failed to compile (modulesPassed == 0)
     */
    REJECTED_BUILD_FAILED,

    /**
     * Repository was rejected because all qualifying merges compiled but every test failed
     */
    REJECTED_ALL_TESTS_FAILED,

    /**
     * Repository was rejected due to a mix of different failure types across merges
     */
    REJECTED_MULTI,

    /**
     * Repository was rejected due to unexpected exceptions during merge processing
     */
    REJECTED_OTHER;
    
    /**
     * Check if this status represents a rejected repository
     * @return true if this is a rejection status
     */
    public boolean isRejected() {
        return name().startsWith("REJECTED");
    }
    
    /**
     * Check if this status represents a successful repository
     * @return true if this is a success status
     */
    public boolean isSuccessful() {
        return this == SUCCESS;
    }
}