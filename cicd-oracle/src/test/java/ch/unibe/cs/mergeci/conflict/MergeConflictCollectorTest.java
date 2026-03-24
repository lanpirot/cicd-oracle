package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.repoCollection.CollectionResult;
import ch.unibe.cs.mergeci.util.RepositoryStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class MergeConflictCollectorTest extends BaseTest {

    @Test
    void collectDataset() throws Exception {
        MergeConflictCollector collector = new MergeConflictCollector(
                AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest),
                AppConfig.TEST_TMP_DIR,
                AppConfig.getMaxConflictMerges()
        );

        Path outputPath = AppConfig.TEST_DATASET_DIR.resolve(AppConfig.myTest + AppConfig.CSV);

        CollectionResult result = collector.collectDataset(
                outputPath,
                AppConfig.myTest,
                "test-url"
        );

        assertEquals(RepositoryStatus.SUCCESS, result.getStatus(), "Repository should be successfully processed");
        assertTrue(Files.exists(outputPath), "Dataset file should be created: " + outputPath);
        assertTrue(Files.size(outputPath) > 0, "Dataset file should not be empty");
        assertTrue(result.getTotalMerges() > 0, "Should have found merge commits");
        assertTrue(result.getSuccessfulMerges() > 0, "Should have successful merges");
        System.out.println(result.getDetailedStats());
    }
}
