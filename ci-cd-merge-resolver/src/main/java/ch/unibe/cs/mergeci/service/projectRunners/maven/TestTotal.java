package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.util.FileUtils;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
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

    private final File projectDir;

    private List<TestResult> testResults;

    public TestTotal(File projectDir) throws IOException {

        this.projectDir = projectDir;

        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" +
                "**/target/surefire-reports/*.txt");

        List<Path> paths = FileUtils.listFilesUsingFileWalk(projectDir.getPath());
        for (Path file : paths) {
            if (pathMatcher.matches(file)) {
                TestResult testResult = TestResult.createTestResultFromFile(file.toFile());
                if (testResult == null) {

                    continue;}
                runNum += testResult.getRunNum();
                failuresNum += testResult.getFailuresNum();
                errorsNum += testResult.getErrorsNum();
                skippedNum += testResult.getSkippedNum();
                elapsedTime += testResult.getElapsedTime();
            }
        }
    }

    @Override
    public String toString() {
        return "TestTotal{" +
                "runNum=" + runNum +
                ", failuresNum=" + failuresNum +
                ", errorsNum=" + errorsNum +
                ", skippedNum=" + skippedNum +
                ", elapsedTime=" + elapsedTime +
                '}';
    }
}
