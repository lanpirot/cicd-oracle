package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class TestResultTest extends BaseTest {

    @Test
    void createTestResultFromFile() throws IOException {
        // Arrange - use cross-platform path to test resource
        File testFile = AppConfig.TEST_RESOURCE_DIR.resolve("sample-test-result.txt").toFile();

        // Act
        TestResult testResult = TestResult.createTestResultFromFile(testFile);

        // Assert - verify parsing worked correctly
        assertNotNull(testResult, "TestResult should not be null");
        assertEquals(10, testResult.getRunNum(), "Total tests run should be 10");
        assertEquals(2, testResult.getFailuresNum(), "Failures should be 2");
        assertEquals(1, testResult.getErrorsNum(), "Errors should be 1");
        assertEquals(1, testResult.getSkippedNum(), "Skipped tests should be 1");
        assertEquals(3.456f, testResult.getElapsedTime(), 0.001f, "Elapsed time should be 3.456");
        assertEquals(6, testResult.getPassedNum(), "Passed tests should be 6 (10 - 2 - 1 - 1)");
    }

    @Test
    void parsesCommaDecimalElapsedTime() throws IOException {
        // A de_CH/de_DE-locale Maven prints "Time elapsed: 1,769" (comma decimal),
        // which previously crashed Float.parseFloat with "For input string: 1,769".
        File f = AppConfig.TEST_TMP_DIR.resolve("comma-locale-test-result.txt").toFile();
        f.getParentFile().mkdirs();
        java.nio.file.Files.writeString(f.toPath(),
                "Tests run: 1769, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 1,769 s");

        TestResult r = TestResult.createTestResultFromFile(f);

        assertNotNull(r, "comma-decimal elapsed time must parse, not throw");
        assertEquals(1769, r.getRunNum());
        assertEquals(1.769f, r.getElapsedTime(), 0.001f, "'1,769' must parse as 1.769");
    }
}
