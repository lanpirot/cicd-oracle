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
    void createTestMergeCommitsCsvIfMissing() throws Exception {
        if (!Files.exists(AppConfig.TEST_MAVEN_CONFLICTS_CSV)) {
            Files.createDirectories(AppConfig.TEST_MAVEN_CONFLICTS_CSV.getParent());
            String localRepoPath = AppConfig.TEST_REPO_DIR.resolve(AppConfig.myTest).toAbsolutePath().toString();
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(AppConfig.TEST_MAVEN_CONFLICTS_CSV.toFile()))) {
                writer.write("merge_id,commit_id,project_id,project_name,remote_url,commit_time,is_maven");
                writer.newLine();
                writer.write("1,,1,test/myTest,file://" + localRepoPath + ",2024-01-01,True");
                writer.newLine();
            }
        }
    }

    @Test
    void processCsv() throws Exception {
        RepoCollector repoCollector = new RepoCollector(
            AppConfig.TEST_TMP_DIR.resolve("repos"),
            AppConfig.TEST_TMP_DIR,
            AppConfig.TEST_DATASET_DIR
        );

        assertTrue(Files.exists(AppConfig.TEST_MAVEN_CONFLICTS_CSV),
            "Test maven_conflicts.csv should exist");

        repoCollector.processCsv(AppConfig.TEST_MAVEN_CONFLICTS_CSV);

        assertTrue(Files.exists(AppConfig.TEST_DATASET_DIR),
            "Dataset directory should exist after processing");
    }
}
