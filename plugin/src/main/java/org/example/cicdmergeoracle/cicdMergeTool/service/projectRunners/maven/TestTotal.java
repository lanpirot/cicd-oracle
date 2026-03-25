package org.example.cicdmergeoracle.cicdMergeTool.service.projectRunners.maven;

import org.example.cicdmergeoracle.cicdMergeTool.util.MyFileUtils;
import lombok.Getter;
import lombok.Setter;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.List;

@Getter
@Setter
public class TestTotal {
    private int runNum;
    private int failuresNum;
    private int errorsNum;
    private int skippedNum;
    private float elapsedTime;

    private File projectDir;

    public TestTotal() {
    }

    public TestTotal(File projectDir) {

        this.projectDir = projectDir;

        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" +
                "**/target/surefire-reports/*.txt");

        List<Path> paths = null;
        try {
            paths = MyFileUtils.listFilesUsingFileWalk(projectDir.toPath());


            for (Path file : paths) {
                if (pathMatcher.matches(file)) {
                    TestResult testResult = TestResult.createTestResultFromFile(file.toFile());
//                System.out.println(file.toString()+": "+testResult.getRunNum());
                    if (testResult == null) {
                        continue;
                    }
                    runNum += testResult.getRunNum();
                    failuresNum += testResult.getFailuresNum();
                    errorsNum += testResult.getErrorsNum();
                    skippedNum += testResult.getSkippedNum();
                    elapsedTime += testResult.getElapsedTime();
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

/*    @Override
    public String toString() {
        return "TestTotal{" +
                "\n\t runNum=" + runNum +
                "\n\t failuresNum=" + failuresNum +
                "\n\t errorsNum=" + errorsNum +
                "\n\t skippedNum=" + skippedNum +
                "\n\t elapsedTime=" + elapsedTime +
                '}';
    }*/

    @Override
    public String toString() {
        return "Tests: " + (runNum - failuresNum - errorsNum - skippedNum);
    }
}
