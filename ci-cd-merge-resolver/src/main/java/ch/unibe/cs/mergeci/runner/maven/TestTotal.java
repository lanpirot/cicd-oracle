package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.util.FileUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
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
@JsonIgnoreProperties(ignoreUnknown = true)
public class TestTotal {
    private int runNum;
    private int failuresNum;
    private int errorsNum;
    private int skippedNum;
    private float elapsedTime;

    /** True only when at least one surefire/failsafe report was successfully parsed.
     *  False means no test reports were found — distinguishable from "ran 0 tests". */
    private boolean hasData;

    @JsonIgnore
    private File projectDir;

    @JsonProperty
    public int getPassedTests() {
        return runNum - failuresNum - errorsNum - skippedNum;
    }

    public TestTotal() {
    }

    public TestTotal(File projectDir) {

        this.projectDir = projectDir;

        PathMatcher pathMatcher = FileSystems.getDefault().getPathMatcher("glob:" +
                "**/target/surefire-reports/*.txt");
        PathMatcher failsafeMatcher = FileSystems.getDefault().getPathMatcher("glob:" +
                "**/target/failsafe-reports/*.txt");

        List<Path> paths = null;
        try {
            paths = FileUtils.listFilesUsingFileWalk(projectDir.toPath());


        for (Path file : paths) {
            if (pathMatcher.matches(file) || failsafeMatcher.matches(file)) {
                TestResult testResult = TestResult.createTestResultFromFile(file.toFile());
                if (testResult == null) {continue;}
                runNum += testResult.getRunNum();
                failuresNum += testResult.getFailuresNum();
                errorsNum += testResult.getErrorsNum();
                skippedNum += testResult.getSkippedNum();
                elapsedTime += testResult.getElapsedTime();
                hasData = true;
            }
        }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // JUnit 4 test suites (e.g. PrimarySuite) report 0 tests in the .txt wrapper but the
        // actual counts are in the XML file.  Fall back to XML when .txt files gave us nothing.
        if (runNum == 0 && hasData) {
            try {
                TestTotalXml xml = new TestTotalXml(projectDir);
                if (xml.getRunNum() > 0) {
                    runNum      = xml.getRunNum();
                    failuresNum = xml.getFailuresNum();
                    errorsNum   = xml.getErrorsNum();
                    skippedNum  = xml.getSkippedNum();
                    elapsedTime = xml.getElapsedTime();
                }
            } catch (Exception ignored) {
                // XML fallback is best-effort; keep the txt-based (zero) result on failure
            }
        }
    }

    /**
     * Normalize elapsed time to account for mass test failures.
     * If tests fail, elapsed time is shorter than a fully-green run would be.
     * Scaling by runTests/passedTests estimates the equivalent full-pass duration.
     *
     * @return NaN when no tests passed (budget caller should fall back to raw time)
     */
    public static float normalizeElapsedTime(float compilationTime, float testElapsed,
                                             int runTests, int passedTests) {
        if (passedTests <= 0) return Float.NaN;
        return compilationTime + testElapsed * runTests / passedTests;
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
