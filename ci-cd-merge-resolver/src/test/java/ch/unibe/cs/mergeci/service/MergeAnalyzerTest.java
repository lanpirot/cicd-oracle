package ch.unibe.cs.mergeci.service;

import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeAnalyzerTest {

    @Test
    void buildProjects() throws Exception {
        MergeAnalyzer mergeAnalyzer = new MergeAnalyzer("src/test/resources/test-merge-projects/Discovery", "temp");
//        mergeAnalyzer.buildProjects("a5a645b7496ddd0c4647abc6b1d8334d561f1eb4", "eeef4711d6e2f12a03b2956bda837b06311feea1", "b71b398b74eea4b8684bfdb091c43e58b627403d");
//        mergeAnalyzer.runTests();

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