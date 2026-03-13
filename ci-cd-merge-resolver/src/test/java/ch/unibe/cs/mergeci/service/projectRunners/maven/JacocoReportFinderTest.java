package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.BaseTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for JacocoReportFinder - validates finding and parsing Jacoco XML reports.
 */
class JacocoReportFinderTest extends BaseTest {

    @Test
    void testGetCoverageResults_WithValidReport(@TempDir Path tempDir) throws IOException {
        // Create a mock project structure with jacoco report
        Path projectDir = tempDir.resolve("test-project");
        Path targetDir = projectDir.resolve("target/site/jacoco");
        Files.createDirectories(targetDir);

        // Create a valid jacoco.xml with coverage data matching actual format (compact to avoid text nodes)
        String jacocoXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<report name=\"test-project\">" +
                "<package name=\"com.example\">" +
                "<class name=\"com/example/TestClass\">" +
                "<method name=\"testMethod\" desc=\"()V\" line=\"10\">" +
                "<counter type=\"INSTRUCTION\" missed=\"10\" covered=\"40\"/>" +
                "<counter type=\"LINE\" missed=\"5\" covered=\"15\"/>" +
                "</method>" +
                "</class>" +
                "</package>" +
                "</report>";

        Files.writeString(targetDir.resolve("jacoco.xml"), jacocoXml);

        // Execute
        JacocoReportFinder.CoverageDTO result = JacocoReportFinder.getCoverageResults(
                projectDir,
                Collections.emptyList()
        );

        // Verify
        assertNotNull(result, "Coverage result should not be null");
        assertEquals(0.8f, result.instructionCoverage(), 0.01f, "Instruction coverage should be 40/(40+10) = 0.8");
        assertEquals(0.75f, result.lineCoverage(), 0.01f, "Line coverage should be 15/(15+5) = 0.75");
    }

    @Test
    void testGetCoverageResults_MultiModule(@TempDir Path tempDir) throws IOException {
        // Create a multi-module project with multiple jacoco reports
        Path projectDir = tempDir.resolve("multi-module-project");

        // Module 1
        Path module1 = projectDir.resolve("module1/target/site/jacoco");
        Files.createDirectories(module1);
        String jacocoXml1 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<report name=\"module1\">" +
                "<package name=\"com.example.module1\">" +
                "<class name=\"com/example/module1/Class1\">" +
                "<method name=\"method1\" desc=\"()V\" line=\"10\">" +
                "<counter type=\"INSTRUCTION\" missed=\"10\" covered=\"90\"/>" +
                "<counter type=\"LINE\" missed=\"5\" covered=\"45\"/>" +
                "</method>" +
                "</class>" +
                "</package>" +
                "</report>";
        Files.writeString(module1.resolve("jacoco.xml"), jacocoXml1);

        // Module 2
        Path module2 = projectDir.resolve("module2/target/site/jacoco");
        Files.createDirectories(module2);
        String jacocoXml2 = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<report name=\"module2\">" +
                "<package name=\"com.example.module2\">" +
                "<class name=\"com/example/module2/Class2\">" +
                "<method name=\"method2\" desc=\"()V\" line=\"20\">" +
                "<counter type=\"INSTRUCTION\" missed=\"20\" covered=\"80\"/>" +
                "<counter type=\"LINE\" missed=\"10\" covered=\"40\"/>" +
                "</method>" +
                "</class>" +
                "</package>" +
                "</report>";
        Files.writeString(module2.resolve("jacoco.xml"), jacocoXml2);

        // Execute
        JacocoReportFinder.CoverageDTO result = JacocoReportFinder.getCoverageResults(
                projectDir,
                Collections.emptyList()
        );

        // Verify - should aggregate both modules
        assertNotNull(result, "Coverage result should not be null");
        // Total: instructions covered=170, missed=30 -> 170/200 = 0.85
        assertEquals(0.85f, result.instructionCoverage(), 0.01f, "Should aggregate instruction coverage from both modules");
        // Total: lines covered=85, missed=15 -> 85/100 = 0.85
        assertEquals(0.85f, result.lineCoverage(), 0.01f, "Should aggregate line coverage from both modules");
    }

    @Test
    void testGetCoverageResults_NoReport(@TempDir Path tempDir) throws IOException {
        // Create a project directory with no jacoco report
        Path projectDir = tempDir.resolve("no-report-project");
        Files.createDirectories(projectDir);

        // Execute - should handle gracefully (returns NaN when no reports found)
        JacocoReportFinder.CoverageDTO result = JacocoReportFinder.getCoverageResults(
                projectDir,
                Collections.emptyList()
        );

        // Verify - with no reports, coverage should be NaN (0/0)
        assertNotNull(result, "Coverage result should not be null");
        assertTrue(Float.isNaN(result.instructionCoverage()), "Instruction coverage should be NaN when no reports found");
        assertTrue(Float.isNaN(result.lineCoverage()), "Line coverage should be NaN when no reports found");
    }

    @Test
    void testGetCoverageResults_EmptyReport(@TempDir Path tempDir) throws IOException {
        // Create a project with an empty jacoco report
        Path projectDir = tempDir.resolve("empty-report-project");
        Path targetDir = projectDir.resolve("target/site/jacoco");
        Files.createDirectories(targetDir);

        String emptyJacocoXml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <report name="empty-project">
                </report>
                """;

        Files.writeString(targetDir.resolve("jacoco.xml"), emptyJacocoXml);

        // Execute
        JacocoReportFinder.CoverageDTO result = JacocoReportFinder.getCoverageResults(
                projectDir,
                Collections.emptyList()
        );

        // Verify - with 0 coverage, should return NaN (0/0)
        assertNotNull(result, "Coverage result should not be null");
        assertTrue(Float.isNaN(result.instructionCoverage()), "Instruction coverage should be NaN for empty report");
        assertTrue(Float.isNaN(result.lineCoverage()), "Line coverage should be NaN for empty report");
    }

    @Test
    void testGetCoverageResults_MalformedXml(@TempDir Path tempDir) throws IOException {
        // Create a project with malformed XML
        Path projectDir = tempDir.resolve("malformed-project");
        Path targetDir = projectDir.resolve("target/site/jacoco");
        Files.createDirectories(targetDir);

        String malformedXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<report name=\"malformed\">" +
                "<unclosed-tag>" +
                "</report>";

        Files.writeString(targetDir.resolve("jacoco.xml"), malformedXml);

        // Execute - should throw RuntimeException wrapping SAXException
        assertThrows(RuntimeException.class, () -> {
            JacocoReportFinder.getCoverageResults(projectDir, Collections.emptyList());
        }, "Should throw RuntimeException for malformed XML");
    }

    @Test
    void testGetCoverageResults_NestedModules(@TempDir Path tempDir) throws IOException {
        // Test deeply nested module structure
        Path projectDir = tempDir.resolve("nested-project");
        Path nestedModule = projectDir.resolve("parent/child/grandchild/target/site/jacoco");
        Files.createDirectories(nestedModule);

        String jacocoXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<report name=\"grandchild-module\">" +
                "<package name=\"com.example.nested\">" +
                "<class name=\"com/example/nested/DeepClass\">" +
                "<method name=\"deepMethod\" desc=\"()V\" line=\"100\">" +
                "<counter type=\"INSTRUCTION\" missed=\"25\" covered=\"75\"/>" +
                "<counter type=\"LINE\" missed=\"10\" covered=\"40\"/>" +
                "</method>" +
                "</class>" +
                "</package>" +
                "</report>";

        Files.writeString(nestedModule.resolve("jacoco.xml"), jacocoXml);

        // Execute
        JacocoReportFinder.CoverageDTO result = JacocoReportFinder.getCoverageResults(
                projectDir,
                Collections.emptyList()
        );

        // Verify - should find deeply nested reports
        assertNotNull(result, "Coverage result should not be null");
        assertEquals(0.75f, result.instructionCoverage(), 0.01f, "Should find and parse deeply nested report");
        assertEquals(0.8f, result.lineCoverage(), 0.01f, "Should calculate line coverage correctly");
    }

    @Test
    void testCoverageDTO_RecordProperties() {
        // Test the record properties
        JacocoReportFinder.CoverageDTO dto = new JacocoReportFinder.CoverageDTO(0.85f, 0.90f);

        assertEquals(0.85f, dto.instructionCoverage(), "Instruction coverage should match");
        assertEquals(0.90f, dto.lineCoverage(), "Line coverage should match");
    }

    @Test
    void testGetCoverageResults_ZeroCoverage(@TempDir Path tempDir) throws IOException {
        // Create a project with zero coverage (all missed)
        Path projectDir = tempDir.resolve("zero-coverage-project");
        Path targetDir = projectDir.resolve("target/site/jacoco");
        Files.createDirectories(targetDir);

        String jacocoXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<report name=\"zero-coverage\">" +
                "<package name=\"com.example\">" +
                "<class name=\"com/example/UncoveredClass\">" +
                "<method name=\"uncoveredMethod\" desc=\"()V\" line=\"10\">" +
                "<counter type=\"INSTRUCTION\" missed=\"100\" covered=\"0\"/>" +
                "<counter type=\"LINE\" missed=\"50\" covered=\"0\"/>" +
                "</method>" +
                "</class>" +
                "</package>" +
                "</report>";

        Files.writeString(targetDir.resolve("jacoco.xml"), jacocoXml);

        // Execute
        JacocoReportFinder.CoverageDTO result = JacocoReportFinder.getCoverageResults(
                projectDir,
                Collections.emptyList()
        );

        // Verify - should return 0% coverage
        assertNotNull(result, "Coverage result should not be null");
        assertEquals(0.0f, result.instructionCoverage(), 0.01f, "Instruction coverage should be 0%");
        assertEquals(0.0f, result.lineCoverage(), 0.01f, "Line coverage should be 0%");
    }

    @Test
    void testGetCoverageResults_FullCoverage(@TempDir Path tempDir) throws IOException {
        // Create a project with 100% coverage
        Path projectDir = tempDir.resolve("full-coverage-project");
        Path targetDir = projectDir.resolve("target/site/jacoco");
        Files.createDirectories(targetDir);

        String jacocoXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<report name=\"full-coverage\">" +
                "<package name=\"com.example\">" +
                "<class name=\"com/example/FullyCoveredClass\">" +
                "<method name=\"coveredMethod\" desc=\"()V\" line=\"10\">" +
                "<counter type=\"INSTRUCTION\" missed=\"0\" covered=\"100\"/>" +
                "<counter type=\"LINE\" missed=\"0\" covered=\"25\"/>" +
                "</method>" +
                "</class>" +
                "</package>" +
                "</report>";

        Files.writeString(targetDir.resolve("jacoco.xml"), jacocoXml);

        // Execute
        JacocoReportFinder.CoverageDTO result = JacocoReportFinder.getCoverageResults(
                projectDir,
                Collections.emptyList()
        );

        // Verify - should return 100% coverage
        assertNotNull(result, "Coverage result should not be null");
        assertEquals(1.0f, result.instructionCoverage(), 0.01f, "Instruction coverage should be 100%");
        assertEquals(1.0f, result.lineCoverage(), 0.01f, "Line coverage should be 100%");
    }
}
