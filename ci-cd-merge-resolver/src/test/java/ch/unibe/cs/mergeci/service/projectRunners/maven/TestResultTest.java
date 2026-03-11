package ch.unibe.cs.mergeci.service.projectRunners.maven;

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
}