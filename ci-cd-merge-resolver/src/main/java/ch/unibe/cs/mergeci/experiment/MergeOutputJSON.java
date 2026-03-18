package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
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
@JsonPropertyOrder({"mergeCommit", "parent1", "parent2",
        "numConflictFiles", "numJavaConflictFiles", "numConflictChunks",
        "isMultiModule", "humanBaselineSeconds", "variantBudgetSeconds",
        "totalExecutionTime", "variantsExecutionTimeSeconds",
        "numVariantsAttempted", "budgetExhausted", "coverage", "variants"})
@ToString
public class MergeOutputJSON {
    private String mergeCommit;
    private String parent1;
    private String parent2;

    private int numConflictFiles;
    private int numJavaConflictFiles;
    private int numConflictChunks;
    private Boolean isMultiModule;

    private JacocoReportFinder.CoverageDTO coverage;

    private long humanBaselineSeconds;

    private long variantBudgetSeconds;
    private boolean budgetExhausted;
    private int numVariantsAttempted;

    private long totalExecutionTime;

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
    public static class Variant {
        private int variantIndex;
        private CompilationResult compilationResult;
        private TestTotal testResults;
        private Map<String, List<String>> conflictPatterns;
        private Double ownExecutionSeconds;
        private Double totalTimeSinceMergeStartSeconds;
        private boolean timedOut;
    }

}
