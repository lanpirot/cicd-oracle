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
@JsonPropertyOrder({"mergeCommit", "parent1", "parent2", "numConflictFiles", "numJavaConflictFiles",
        "numConflictChunks", "isMultiModule", "coverage", "humanBaselineSeconds", "totalExecutionTime",
        "variantsExecution"})
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

    private long totalExecutionTime;


    private VariantsExecution variantsExecution;

    /**
     * Returns the baseline (human_baseline) TestTotal by looking at the variants list.
     * Falls back to an empty TestTotal if not available.
     */
    @JsonIgnore
    public TestTotal getTestResults() {
        if (variantsExecution != null && variantsExecution.getVariants() != null) {
            for (Variant v : variantsExecution.getVariants()) {
                if ("human_baseline".equals(v.getVariantName()) && v.getTestResults() != null) {
                    return v.getTestResults();
                }
            }
        }
        return new TestTotal();
    }

    /**
     * Returns the baseline (human_baseline) CompilationResult by looking at the variants list.
     * Returns null if not available.
     */
    @JsonIgnore
    public CompilationResult getCompilationResult() {
        if (variantsExecution != null && variantsExecution.getVariants() != null) {
            for (Variant v : variantsExecution.getVariants()) {
                if ("human_baseline".equals(v.getVariantName()) && v.getCompilationResult() != null) {
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
        private String variantName;
        private CompilationResult compilationResult;
        private TestTotal testResults;
        private Map<String, List<String>> conflictPatterns;
        private Double finishedAfterFirstVariantStartSeconds;
    }

    @Getter
    @Setter
    @JsonPropertyOrder({"executionTimeSeconds", "results"})
    @NoArgsConstructor
    public static class VariantsExecution {
        private long executionTimeSeconds;
        private List<Variant> variants;

        public VariantsExecution(List<Variant> variants) {
            this.variants = variants;
        }
    }

}
