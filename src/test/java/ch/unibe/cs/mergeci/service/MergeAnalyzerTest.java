package ch.unibe.cs.mergeci.service;

import ch.unibe.cs.mergeci.service.projectRunners.maven.TestTotal;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MergeAnalyzerTest {

    @Test
    void buildProjects() throws Exception {
        MergeAnalyzer mergeAnalyzer = new MergeAnalyzer("src/test/resources/test-merge-projects/jackson-databind","temp");
//        mergeAnalyzer.buildProjects("bf08f05406f90cd6a3e76e76687dfe45b22105d5","a36a049147c023becffbea2793042caef3ca3285","7613683b4ab806924cfb44438eed416b1e302438");
//         mergeAnalyzer.runTests();
        Map<String, TestTotal> results = mergeAnalyzer.collectTestResults();
//        System.out.println(results);
    }
}