package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.model.patterns.IPattern;
import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonPropertyOrder({"mergeCommit", "parent1", "parent2", "numJavaConflicts", "numConflictChunks", "totalExecutionTime",
        "compilationResult", "testResults", "modulesResults"})
public class MergeOutputJSON {
    private String mergeCommit;
    private String parent1;
    private String parent2;

    private CompilationResult compilationResult;
    private TestTotal testResults = new TestTotal();

    private int numJavaConflicts;
    private int numConflictChunks;

    private long totalExecutionTime;


    private List<Variant> modulesResults;

    @Getter
    @Setter
    public static class Variant {
        private String variantName;
        private CompilationResult compilationResult;
        private TestTotal testResults;
        private Map<String, List<String>> conflictPatterns;
    }


}
