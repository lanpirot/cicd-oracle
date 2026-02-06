package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;

public class DatasetCollectorTest {

    @Test
    void collectDataset() throws Exception {
        DatasetCollector collector = new DatasetCollector(
                AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind),
                AppConfig.TMP_DIR,
                2
        );

        collector.collectDataset(AppConfig.TEST_DATASET_DIR.resolve(AppConfig.jacksonDatabind + AppConfig.XLSX));
    }
}