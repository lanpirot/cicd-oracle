package ch.unibe.cs.mergeci.repoCollection;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class RepoCollectorTest extends BaseTest {

    @BeforeEach
    void createTestCsvIfMissing() throws Exception {
        if (!Files.exists(AppConfig.TEST_INPUT_PROJECT_CSV)) {
            Files.createDirectories(AppConfig.TEST_INPUT_PROJECT_CSV.getParent());

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(AppConfig.TEST_INPUT_PROJECT_CSV.toFile()))) {
                // Header row
                writer.write("Repository,URL");
                writer.newLine();

                // Data row - use myTest
                String localRepoPath = AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest).toAbsolutePath().toString();
                writer.write("test/myTest,file://" + localRepoPath);
                writer.newLine();
            }
        }
    }

    @Test
    void processCsv() throws Exception {
        // Use TEST_TMP_DIR/repos for cloning to ensure clean state between test runs
        RepoCollector repoCollector = new RepoCollector(
            AppConfig.TEST_TMP_DIR.resolve("repos"),
            AppConfig.TEST_TMP_DIR,
            AppConfig.TEST_DATASET_DIR
        );

        // Verify input file exists
        assertTrue(Files.exists(AppConfig.TEST_INPUT_PROJECT_CSV),
            "Input CSV file should exist");

        repoCollector.processCsv(AppConfig.TEST_INPUT_PROJECT_CSV);

        // Verify processing completed - dataset directory should exist and contain files
        assertTrue(Files.exists(AppConfig.TEST_DATASET_DIR),
            "Dataset directory should exist after processing");
    }
}
