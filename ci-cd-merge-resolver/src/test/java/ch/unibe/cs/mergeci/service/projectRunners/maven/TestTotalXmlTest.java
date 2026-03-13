package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.BaseTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TestTotalXml - validates Maven Surefire XML test report parsing.
 */
public class TestTotalXmlTest extends BaseTest {

    @Test
    void testParseSingleXmlReport(@TempDir Path tempDir) throws Exception {
        // Create project directory with surefire report
        Path projectDir = tempDir.resolve("test-project");
        Path surefireDir = projectDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        // Create a valid surefire XML report
        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.TestClass" tests="10" failures="2" errors="1" skipped="1" time="5.5">
                  <testcase name="test1" time="1.0"/>
                  <testcase name="test2" time="2.0">
                    <failure message="assertion failed"/>
                  </testcase>
                </testsuite>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.TestClass.xml"), xmlContent);

        // Execute
        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        // Verify
        assertEquals(10, testTotal.getRunNum(), "Should parse tests count");
        assertEquals(2, testTotal.getFailuresNum(), "Should parse failures count");
        assertEquals(1, testTotal.getErrorsNum(), "Should parse errors count");
        assertEquals(1, testTotal.getSkippedNum(), "Should parse skipped count");
        assertEquals(5.5f, testTotal.getElapsedTime(), 0.01f, "Should parse elapsed time");
    }

    @Test
    void testParseMultipleXmlReports(@TempDir Path tempDir) throws Exception {
        // Create project directory with multiple surefire reports
        Path projectDir = tempDir.resolve("test-project");
        Path surefireDir = projectDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        // Create first report
        String xml1 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Test1" tests="5" failures="1" errors="0" skipped="0" time="2.5">
                </testsuite>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.Test1.xml"), xml1);

        // Create second report
        String xml2 = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Test2" tests="8" failures="2" errors="1" skipped="1" time="3.0">
                </testsuite>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.Test2.xml"), xml2);

        // Execute
        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        // Verify aggregation
        assertEquals(13, testTotal.getRunNum(), "Should aggregate tests: 5 + 8 = 13");
        assertEquals(3, testTotal.getFailuresNum(), "Should aggregate failures: 1 + 2 = 3");
        assertEquals(1, testTotal.getErrorsNum(), "Should aggregate errors: 0 + 1 = 1");
        assertEquals(1, testTotal.getSkippedNum(), "Should aggregate skipped: 0 + 1 = 1");
        assertEquals(5.5f, testTotal.getElapsedTime(), 0.01f, "Should aggregate time: 2.5 + 3.0 = 5.5");
    }

    @Test
    void testParseMultiModuleProject(@TempDir Path tempDir) throws Exception {
        // Create multi-module project structure
        Path projectDir = tempDir.resolve("multi-module-project");
        
        // Module 1
        Path module1SurefireDir = projectDir.resolve("module1/target/surefire-reports");
        Files.createDirectories(module1SurefireDir);
        String module1Xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.module1.Test" tests="10" failures="1" errors="0" skipped="0" time="4.0">
                </testsuite>
                """;
        Files.writeString(module1SurefireDir.resolve("TEST-com.module1.Test.xml"), module1Xml);

        // Module 2
        Path module2SurefireDir = projectDir.resolve("module2/target/surefire-reports");
        Files.createDirectories(module2SurefireDir);
        String module2Xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.module2.Test" tests="15" failures="2" errors="1" skipped="1" time="6.0">
                </testsuite>
                """;
        Files.writeString(module2SurefireDir.resolve("TEST-com.module2.Test.xml"), module2Xml);

        // Execute
        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        // Verify aggregation across modules
        assertEquals(25, testTotal.getRunNum(), "Should aggregate across modules: 10 + 15 = 25");
        assertEquals(3, testTotal.getFailuresNum(), "Should aggregate failures: 1 + 2 = 3");
        assertEquals(1, testTotal.getErrorsNum(), "Should aggregate errors: 0 + 1 = 1");
        assertEquals(1, testTotal.getSkippedNum(), "Should aggregate skipped: 0 + 1 = 1");
        assertEquals(10.0f, testTotal.getElapsedTime(), 0.01f, "Should aggregate time: 4.0 + 6.0 = 10.0");
    }

    @Test
    void testParseNoReports(@TempDir Path tempDir) throws Exception {
        // Create project directory without any surefire reports
        Path projectDir = tempDir.resolve("no-reports-project");
        Files.createDirectories(projectDir);

        // Execute
        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        // Verify all counts are zero
        assertEquals(0, testTotal.getRunNum(), "Should have zero tests");
        assertEquals(0, testTotal.getFailuresNum(), "Should have zero failures");
        assertEquals(0, testTotal.getErrorsNum(), "Should have zero errors");
        assertEquals(0, testTotal.getSkippedNum(), "Should have zero skipped");
        assertEquals(0.0f, testTotal.getElapsedTime(), 0.01f, "Should have zero elapsed time");
    }

    @Test
    void testParseAllTestsPass(@TempDir Path tempDir) throws Exception {
        // Create report with all tests passing
        Path projectDir = tempDir.resolve("all-pass-project");
        Path surefireDir = projectDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.PassingTest" tests="20" failures="0" errors="0" skipped="0" time="10.5">
                </testsuite>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.PassingTest.xml"), xmlContent);

        // Execute
        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        // Verify all pass
        assertEquals(20, testTotal.getRunNum());
        assertEquals(0, testTotal.getFailuresNum());
        assertEquals(0, testTotal.getErrorsNum());
        assertEquals(0, testTotal.getSkippedNum());
    }

    @Test
    void testParseAllTestsFail(@TempDir Path tempDir) throws Exception {
        // Create report with all tests failing
        Path projectDir = tempDir.resolve("all-fail-project");
        Path surefireDir = projectDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.FailingTest" tests="10" failures="7" errors="3" skipped="0" time="5.0">
                </testsuite>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.FailingTest.xml"), xmlContent);

        // Execute
        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        // Verify
        assertEquals(10, testTotal.getRunNum());
        assertEquals(7, testTotal.getFailuresNum());
        assertEquals(3, testTotal.getErrorsNum());
        assertEquals(0, testTotal.getSkippedNum());
    }

    @Test
    void testParseMalformedXml(@TempDir Path tempDir) throws IOException {
        // Create project with malformed XML
        Path projectDir = tempDir.resolve("malformed-project");
        Path surefireDir = projectDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        String malformedXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Test" tests="10" failures="1"
                <unclosed-tag>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.Test.xml"), malformedXml);

        // Execute - should throw exception for malformed XML
        assertThrows(Exception.class, () -> new TestTotalXml(projectDir.toFile()),
                "Should throw exception for malformed XML");
    }

    @Test
    void testParseIgnoresNonTestFiles(@TempDir Path tempDir) throws Exception {
        // Create project with both test and non-test files
        Path projectDir = tempDir.resolve("mixed-files-project");
        Path surefireDir = projectDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        // Valid test file
        String testXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Test" tests="5" failures="0" errors="0" skipped="0" time="2.0">
                </testsuite>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.Test.xml"), testXml);

        // Non-matching files (should be ignored)
        Files.writeString(surefireDir.resolve("summary.xml"), "<summary>data</summary>");
        Files.writeString(surefireDir.resolve("com.example.Test.txt"), "text file");

        // Execute
        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        // Verify only TEST-*.xml files are processed
        assertEquals(5, testTotal.getRunNum(), "Should only process TEST-*.xml files");
    }

    @Test
    void testToString(@TempDir Path tempDir) throws Exception {
        // Create simple project
        Path projectDir = tempDir.resolve("test-project");
        Path surefireDir = projectDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Test" tests="10" failures="2" errors="1" skipped="1" time="5.5">
                </testsuite>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.Test.xml"), xmlContent);

        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        String toString = testTotal.toString();
        assertNotNull(toString);
        assertTrue(toString.contains("runNum=10"));
        assertTrue(toString.contains("failuresNum=2"));
        assertTrue(toString.contains("errorsNum=1"));
        assertTrue(toString.contains("skippedNum=1"));
        assertTrue(toString.contains("elapsedTime=5.5"));
    }

    @Test
    void testParseDecimalTime(@TempDir Path tempDir) throws Exception {
        // Test various decimal formats for time
        Path projectDir = tempDir.resolve("decimal-time-project");
        Path surefireDir = projectDir.resolve("target/surefire-reports");
        Files.createDirectories(surefireDir);

        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.example.Test" tests="5" failures="0" errors="0" skipped="0" time="1.234567">
                </testsuite>
                """;
        Files.writeString(surefireDir.resolve("TEST-com.example.Test.xml"), xmlContent);

        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        assertEquals(1.234567f, testTotal.getElapsedTime(), 0.000001f, 
                "Should parse high-precision decimal times");
    }

    @Test
    void testParseNestedModules(@TempDir Path tempDir) throws Exception {
        // Test deeply nested module structure
        Path projectDir = tempDir.resolve("nested-project");
        Path nestedSurefireDir = projectDir.resolve("parent/child/grandchild/target/surefire-reports");
        Files.createDirectories(nestedSurefireDir);

        String xmlContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <testsuite name="com.deep.Test" tests="3" failures="0" errors="0" skipped="0" time="1.5">
                </testsuite>
                """;
        Files.writeString(nestedSurefireDir.resolve("TEST-com.deep.Test.xml"), xmlContent);

        TestTotalXml testTotal = new TestTotalXml(projectDir.toFile());

        assertEquals(3, testTotal.getRunNum(), "Should find reports in deeply nested modules");
    }
}
