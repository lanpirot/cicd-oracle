package ch.unibe.cs.mergeci.service;

import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Map;

class MergeAnalyzerTest {

    @Test
    void buildProjects() throws Exception {
        MergeAnalyzer mergeAnalyzer = new MergeAnalyzer(new File("src/test/resources/test-merge-projects/zemberek-nlp"), "temp");
        mergeAnalyzer.buildProjects("c10e035c4b36e0b4cd50e009fb94b67e8fc51a45", "356fa0178ca851a1ccee41c7a1846a1a19abbd6b", "4b39a3ee35ffcf61f66a783dde2af1d9fbd9c12a");
        mergeAnalyzer.runTests(new MavenExecutionFactory(mergeAnalyzer.getLogDir()).createMavenRunner(true,false));

        System.out.println("Compilation result:");
        Map<String, CompilationResult> compilationResultMap = mergeAnalyzer.collectCompilationResults();
        compilationResultMap.forEach((k, v) -> {
            System.out.println(k + ": " + v);
        });

        System.out.println("\n\nTesting result:");
        Map<String, TestTotal> testTotalMap = mergeAnalyzer.collectTestResults();
        testTotalMap.forEach((k, v) -> {
            System.out.println(k + ": " + v);
        });
    }
}