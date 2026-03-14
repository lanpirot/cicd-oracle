package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.JacocoReportFinder;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonPropertyOrder({"mergeCommit", "parent1", "parent2", "numConflictFiles", "numJavaConflictFiles",
        "numConflictChunks", "isMultiModule", "coverage", "totalExecutionTime",
        "compilationResult", "testResults", "modulesResults"})
@ToString
public class MergeOutputJSON {
    private String mergeCommit;
    private String parent1;
    private String parent2;

    private CompilationResult compilationResult;
    private TestTotal testResults = new TestTotal();

    private int numConflictFiles;
    private int numJavaConflictFiles;
    private int numConflictChunks;
    private Boolean isMultiModule;

    private JacocoReportFinder.CoverageDTO coverage;

    private long totalExecutionTime;


    private VariantsExecution variantsExecution;

    @Getter
    @Setter
    public static class Variant {
        private String variantName;
        private CompilationResult compilationResult;
        private TestTotal testResults;
        private Map<String, List<String>> conflictPatterns;
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
