package ch.unibe.cs.mergeci.util;

/**
 * Enum representing the status of a repository in the processing pipeline.
 */
public enum RepositoryStatus {
    /**
     * Repository has not been processed yet
     */
    NOT_PROCESSED,
    
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
     * Repository was rejected because it has no Java conflicts
     */
    REJECTED_NO_JAVA_CONFLICTS,
    
    /**
     * Repository was rejected because it has no tests
     */
    REJECTED_NO_TESTS,
    
    /**
     * Repository was rejected for other reasons
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