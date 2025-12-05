package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class MergeOutputJSON {
    private String mergeCommit;
    private String parent1;
    private String parent2;

    private CompilationResult compilationResult;
    private TestTotal testResults = new TestTotal();

    private int numJavaConflicts;
    private int numConflictChunks;

    private long variantsExecutionTime;

    private List<Variant> modulesResults;

    @Getter
    @Setter
    public static class Variant{
        private String variantName;
        private CompilationResult compilationResult;
        private TestTotal testResults;
    }

}
