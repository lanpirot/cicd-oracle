package ch.unibe.cs.mergeci.experimentSetup.coverageCalculater;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.AllMergesJSON;
import ch.unibe.cs.mergeci.experimentSetup.evaluationCollection.MergeOutputJSON;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for CoverageCalculator - validates coverage calculation setup and configuration.
 * Note: CoverageCalculator is currently marked as unused/out of sync in production code,
 * so these tests focus on basic construction and validation.
 */
public class CoverageCalculatorTest extends BaseTest {

    private Path inputFolder;
    private Path repoDatasetsFile;
    private Path tempDir;
    private Path cloneDir;
    private CoverageCalculator calculator;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        inputFolder = tempDir.resolve("input");
        repoDatasetsFile = tempDir.resolve("datasets.xlsx");
        this.tempDir = tempDir.resolve("temp");
        cloneDir = tempDir.resolve("clones");

        Files.createDirectories(inputFolder);
        Files.createDirectories(this.tempDir);
        Files.createDirectories(cloneDir);
        Files.createFile(repoDatasetsFile);

        calculator = new CoverageCalculator(
                inputFolder.toFile(),
                repoDatasetsFile.toFile(),
                this.tempDir.toFile(),
                cloneDir.toFile()
        );
    }

    @Test
    void testConstructor_ValidPaths() {
        assertNotNull(calculator, "CoverageCalculator should be instantiated");
    }

    @Test
    void testConstructor_WithNonExistentPaths() {
        File nonExistentFolder = new File("/tmp/non-existent-folder-coverage-12345");
        File nonExistentFile = new File("/tmp/non-existent-file-coverage-12345.xlsx");
        File nonExistentTemp = new File("/tmp/non-existent-temp-coverage-12345");
        File nonExistentClone = new File("/tmp/non-existent-clone-coverage-12345");

        // Constructor should not throw even with non-existent paths
        assertDoesNotThrow(() -> new CoverageCalculator(
                nonExistentFolder,
                nonExistentFile,
                nonExistentTemp,
                nonExistentClone
        ), "Constructor should not validate path existence");
    }

    @Test
    void testCalculateCoverage_EmptyInputFolder(@TempDir Path tempDir) throws IOException {
        Path emptyInput = tempDir.resolve("empty-input");
        Path output = tempDir.resolve("output");
        Files.createDirectories(emptyInput);
        Files.createDirectories(output);

        CoverageCalculator calc = new CoverageCalculator(
                emptyInput.toFile(),
                repoDatasetsFile.toFile(),
                this.tempDir.toFile(),
                cloneDir.toFile()
        );

        // Should not crash with empty input folder
        assertDoesNotThrow(() -> calc.calculateCoverage(output.toFile()),
                "Should handle empty input folder gracefully");
    }

    @Test
    void testCalculateCoverage_CreatesOutputDirectory(@TempDir Path tempDir) throws IOException {
        Path output = tempDir.resolve("output-new");

        CoverageCalculator calc = new CoverageCalculator(
                inputFolder.toFile(),
                repoDatasetsFile.toFile(),
                this.tempDir.toFile(),
                cloneDir.toFile()
        );

        calc.calculateCoverage(output.toFile());

        assertTrue(output.toFile().exists(), "Output directory should be created");
        assertTrue(output.toFile().isDirectory(), "Output should be a directory");
    }

    @Test
    void testCalculateCoverage_SkipsExistingFiles(@TempDir Path tempDir) throws IOException {
        // Create input JSON file
        Path inputJsonFile = inputFolder.resolve("test-project.json");
        AllMergesJSON testData = createTestAllMergesJSON("test-project", List.of());
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        mapper.writeValue(inputJsonFile.toFile(), testData);

        // Create output directory with existing file
        Path output = tempDir.resolve("output");
        Files.createDirectories(output);
        Path existingOutput = output.resolve("test-project.json");
        Files.writeString(existingOutput, "{\"projectName\":\"existing\"}");

        CoverageCalculator calc = new CoverageCalculator(
                inputFolder.toFile(),
                repoDatasetsFile.toFile(),
                this.tempDir.toFile(),
                cloneDir.toFile()
        );

        calc.calculateCoverage(output.toFile());

        // Verify existing file was not overwritten (skipped)
        String content = Files.readString(existingOutput);
        assertTrue(content.contains("existing"), "Existing file should be skipped, not overwritten");
    }

    @Test
    void testCalculateCoverage_HandlesInvalidJSON(@TempDir Path tempDir) throws IOException {
        // Create invalid JSON file
        Path invalidJsonFile = inputFolder.resolve("invalid.json");
        Files.writeString(invalidJsonFile, "{invalid json content");

        Path output = tempDir.resolve("output");
        Files.createDirectories(output);

        CoverageCalculator calc = new CoverageCalculator(
                inputFolder.toFile(),
                repoDatasetsFile.toFile(),
                this.tempDir.toFile(),
                cloneDir.toFile()
        );

        // Should throw RuntimeException wrapping the JSON parsing error
        assertThrows(RuntimeException.class, () -> calc.calculateCoverage(output.toFile()),
                "Should throw RuntimeException for invalid JSON");
    }

    @Test
    void testCalculateCoverage_HandlesNullInputFolder(@TempDir Path tempDir) {
        Path output = tempDir.resolve("output");

        CoverageCalculator calc = new CoverageCalculator(
                null,
                repoDatasetsFile.toFile(),
                this.tempDir.toFile(),
                cloneDir.toFile()
        );

        // Should throw NullPointerException when trying to list files
        assertThrows(NullPointerException.class, () -> calc.calculateCoverage(output.toFile()),
                "Should throw NullPointerException for null input folder");
    }

    @Test
    void testCalculateCoverage_HandlesNonDirectoryInput(@TempDir Path tempDir) throws IOException {
        // Create a file instead of directory
        Path fileNotDir = tempDir.resolve("file-not-dir.txt");
        Files.writeString(fileNotDir, "not a directory");

        Path output = tempDir.resolve("output");

        CoverageCalculator calc = new CoverageCalculator(
                fileNotDir.toFile(),
                repoDatasetsFile.toFile(),
                this.tempDir.toFile(),
                cloneDir.toFile()
        );

        // Should handle gracefully (listFiles returns null for non-directories)
        assertThrows(NullPointerException.class, () -> calc.calculateCoverage(output.toFile()),
                "Should throw when input is not a directory");
    }

    // Helper methods

    private AllMergesJSON createTestAllMergesJSON(String projectName, List<MergeOutputJSON> merges) {
        AllMergesJSON allMerges = new AllMergesJSON();
        allMerges.setProjectName(projectName);
        allMerges.setRepoUrl("https://github.com/test/repo");
        allMerges.setMerges(merges != null ? merges : new ArrayList<>());
        return allMerges;
    }
}