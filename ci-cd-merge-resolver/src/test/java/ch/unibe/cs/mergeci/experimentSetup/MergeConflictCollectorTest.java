package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class MergeConflictCollectorTest extends BaseTest {

    @Test
    void collectDataset() throws Exception {
        MergeConflictCollector collector = new MergeConflictCollector(
                AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind),
                AppConfig.TMP_DIR,
                AppConfig.MAX_CONFLICT_MERGES
        );

        Path outputPath = AppConfig.TEST_DATASET_DIR.resolve(AppConfig.jacksonDatabind + AppConfig.XLSX);

        collector.collectDataset(outputPath);

        // Verify the dataset file was created
        assertTrue(Files.exists(outputPath), "Dataset file should be created: " + outputPath);
        assertTrue(Files.size(outputPath) > 0, "Dataset file should not be empty");
    }
}