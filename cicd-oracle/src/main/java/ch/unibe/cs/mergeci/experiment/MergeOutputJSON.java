package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonPropertyOrder({"mode", "projectName", "mergeCommit", "parent1", "parent2",
        "numConflictFiles", "numJavaConflictFiles", "numConflictChunks",
        "isMultiModule", "baselineBroken", "baselineFailureType", "buildFileConflictMarkers",
        "budgetBasisSeconds", "variantBudgetSeconds",
        "totalExecutionTime", "numInFlightVariantsKilled", "budgetExhausted",
        "variantsExecutionTimeSeconds", "variants"})
@ToString
public class MergeOutputJSON {
    private String mode;
    private String projectName;
    private String mergeCommit;
    private String parent1;
    private String parent2;

    private int numConflictFiles;
    private int numJavaConflictFiles;
    private int numConflictChunks;
    private Boolean isMultiModule;

    /** True when the human baseline build fails to compile. */
    private boolean baselineBroken;

    /** Classification of the baseline failure: INFRA_FAILURE, BROKEN_MERGE, COMPILE_FAILURE,
     *  TIMEOUT, NO_TESTS, or null when the baseline compiled and ran tests successfully. */
    private String baselineFailureType;

    /** True when build descriptor files (pom.xml, package.json, *.gradle) contain unresolved
     *  git conflict markers.  An INFRA_FAILURE with this flag set is potentially fixable by
     *  a variant that resolves the build file conflict differently. */
    private boolean buildFileConflictMarkers;

    /** Baseline build time used to size the variant budget. For variant modes this is read from
     *  the prior human_baseline JSON (not re-measured); for human_baseline mode it is measured. */
    private long budgetBasisSeconds;

    private long variantBudgetSeconds;
    private long totalExecutionTime;
    private int numInFlightVariantsKilled;
    private boolean budgetExhausted;

    private long variantsExecutionTimeSeconds;
    private List<Variant> variants;

    /**
     * Returns the baseline (index 0) TestTotal by looking at the variants list.
     * Falls back to null if not available.
     */
    @JsonIgnore
    public TestTotal getTestResults() {
        if (variants != null) {
            for (Variant v : variants) {
                if (v.getVariantIndex() == 0 && v.getTestResults() != null) {
                    return v.getTestResults();
                }
            }
        }
        return null;
    }

    /**
     * Returns the baseline (index 0) CompilationResult by looking at the variants list.
     * Returns null if not available.
     */
    @JsonIgnore
    public CompilationResult getCompilationResult() {
        if (variants != null) {
            for (Variant v : variants) {
                if (v.getVariantIndex() == 0 && v.getCompilationResult() != null) {
                    return v.getCompilationResult();
                }
            }
        }
        return null;
    }

    @Getter
    @Setter
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonPropertyOrder({"variantIndex", "isCacheWarmer", "ownExecutionSeconds",
            "totalTimeSinceMergeStartSeconds", "timedOut",
            "compilationResult", "testResults", "conflictPatterns"})
    public static class Variant {
        private int variantIndex;
        @JsonProperty("isCacheWarmer")
        private boolean isCacheWarmer;
        private Double ownExecutionSeconds;
        private Double totalTimeSinceMergeStartSeconds;
        private boolean timedOut;
        private CompilationResult compilationResult;
        private TestTotal testResults;
        private Map<String, List<String>> conflictPatterns;
    }

}
