package ch.unibe.cs.mergeci.service;

import ch.unibe.cs.mergeci.service.projectRunners.maven.CompilationResult;
import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeAnalyzerTest {

    @Test
    void buildProjects() throws Exception {
        MergeAnalyzer mergeAnalyzer = new MergeAnalyzer("src/test/resources/test-merge-projects/jackson-databind", "temp");
        mergeAnalyzer.buildProjects("bf08f05406f90cd6a3e76e76687dfe45b22105d5", "a36a049147c023becffbea2793042caef3ca3285", "7613683b4ab806924cfb44438eed416b1e302438");
        mergeAnalyzer.runTests();

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