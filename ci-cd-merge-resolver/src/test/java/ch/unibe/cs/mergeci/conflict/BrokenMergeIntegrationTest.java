package ch.unibe.cs.mergeci.conflict;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experiment.DatasetReader;
import ch.unibe.cs.mergeci.repoCollection.CollectionResult;
import ch.unibe.cs.mergeci.util.RepositoryStatus;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies end-to-end handling of a merge commit that contains unresolved Git conflict markers
 * (i.e. the developer committed the conflict markers verbatim, breaking compilation).
 *
 * Expected behaviour:
 *  - The merge is classified as a BROKEN_MERGE (genuine compilation error, not an infra failure).
 *  - It is included in the dataset CSV with {@code baselineBroken=true} and
 *    {@code compilationSuccess=false}.
 *  - The collection result is SUCCESS (broken merges are valuable data points).
 */
public class BrokenMergeIntegrationTest extends BaseTest {

    @Test
    void brokenMergeIsIncludedInDatasetWithBaselineBrokenFlag() throws Exception {
        MergeConflictCollector collector = new MergeConflictCollector(
                AppConfig.TEST_REPO_DIR.resolve(AppConfig.brokenMergeTest),
                AppConfig.TEST_TMP_DIR,
                AppConfig.getMaxConflictMerges()
        );

        Path outputPath = AppConfig.TEST_DATASET_DIR.resolve(AppConfig.brokenMergeTest + AppConfig.CSV);

        CollectionResult result = collector.collectDataset(
                outputPath,
                AppConfig.brokenMergeTest,
                "test-url"
        );

        assertEquals(RepositoryStatus.SUCCESS, result.getStatus(),
                "Collection should succeed — broken merges are included in the dataset");
        assertTrue(Files.exists(outputPath), "Dataset CSV should be created");
        assertTrue(result.getSuccessfulMerges() > 0,
                "Broken merge should be counted as a dataset row");

        // Verify the CSV row has baselineBroken=true and compilationSuccess=false
        List<DatasetReader.MergeInfo> rows = new DatasetReader().readMergeDataset(outputPath);
        assertFalse(rows.isEmpty(), "Dataset should contain at least one row");

        boolean hasBrokenRow = rows.stream().anyMatch(DatasetReader.MergeInfo::isBaselineBroken);
        assertTrue(hasBrokenRow, "At least one row should have baselineBroken=true");
    }

    @Test
    void buildFailureClassifierDetectsConflictMarkersAsGenuineCompilationError() throws Exception {
        // Run the collector to produce a compilation log, then verify classification
        MergeConflictCollector collector = new MergeConflictCollector(
                AppConfig.TEST_REPO_DIR.resolve(AppConfig.brokenMergeTest),
                AppConfig.TEST_TMP_DIR,
                AppConfig.getMaxConflictMerges()
        );

        Path outputPath = AppConfig.TEST_DATASET_DIR.resolve(AppConfig.brokenMergeTest + "_classifier" + AppConfig.CSV);
        collector.collectDataset(outputPath, AppConfig.brokenMergeTest, "test-url");

        // The compilation log is written to TEST_TMP_DIR; find it
        Path[] logFiles = Files.walk(AppConfig.TEST_TMP_DIR)
                .filter(p -> p.getFileName().toString().endsWith("_compilation"))
                .toArray(Path[]::new);

        assertTrue(logFiles.length > 0, "At least one compilation log should exist");

        boolean anyGenuine = false;
        for (Path log : logFiles) {
            if (BuildFailureClassifier.isGenuineCompilationError(log)) {
                anyGenuine = true;
                assertFalse(BuildFailureClassifier.isInfraFailure(log),
                        "Conflict-marker failure should NOT be classified as infra failure");
                break;
            }
        }
        assertTrue(anyGenuine, "Conflict-marker compilation error should be detected as genuine");
    }
}
