package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;

public class DatasetCollectorTest {

    @Test
    void collectDataset() throws Exception {
        DatasetCollector collector = new DatasetCollector(
                new File(AppConfig.TEST_REPO_DIR, AppConfig.jacksonDatabind).getAbsolutePath(),
                AppConfig.TMP_DIR.getAbsolutePath(),
                2
        );

        collector.collectDataset(new File(AppConfig.TEST_DATASET_DIR,AppConfig.jacksonDatabind + AppConfig.XLSX));
    }
}