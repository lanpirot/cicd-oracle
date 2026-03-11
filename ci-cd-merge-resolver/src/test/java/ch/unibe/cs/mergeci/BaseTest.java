package ch.unibe.cs.mergeci;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

/**
 * Base test class that provides centralized cleanup for test directories.
 * All test classes should extend this to ensure proper cleanup of test artifacts.
 *
 * This class automatically cleans up the following directories before and after each test:
 * - TEST_TMP_DIR: Temporary working directory for tests
 * - TEST_EXPERIMENTS_TEMP_DIR: Temporary directory for experiment outputs
 * - TEST_COVERAGE_DIR: Directory for coverage reports
 * - TEST_DATASET_DIR: Directory for test datasets
 *
 * Note: TEST_REPO_DIR (src/test/resources/test-merge-projects) is NOT cleaned up
 * as it contains version-controlled test fixtures.
 */
public abstract class BaseTest {

    /**
     * Set up method that runs before each test.
     * Cleans all test directories to ensure a clean state.
     */
    @BeforeEach
    void setUpBase() throws IOException {
        cleanTestDirectories();
    }

    /**
     * Tear down method that runs after each test.
     * Cleans all test directories to prevent leftover artifacts.
     */
    @AfterEach
    void tearDownBase() throws IOException {
        cleanTestDirectories();
    }

    /**
     * Cleans all standard test directories.
     */
    private void cleanTestDirectories() throws IOException {
        cleanDirectory(AppConfig.TEST_TMP_DIR);
        cleanDirectory(AppConfig.TEST_EXPERIMENTS_TEMP_DIR);
        cleanDirectory(AppConfig.TEST_COVERAGE_DIR);
        cleanDirectory(AppConfig.TEST_DATASET_DIR);
    }

    /**
     * Recursively deletes all contents of a directory.
     * The directory itself is also removed.
     *
     * @param dir the directory to clean
     * @throws IOException if deletion fails
     */
    protected void cleanDirectory(Path dir) throws IOException {
        if (Files.exists(dir)) {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        System.err.println("Warning: Failed to delete " + path + ": " + e.getMessage());
                    }
                });
        }
    }

    /**
     * Creates a directory if it doesn't exist.
     * Useful for test setup when you need to ensure a directory exists.
     *
     * @param dir the directory to create
     * @throws IOException if directory creation fails
     */
    protected void ensureDirectoryExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }
}
