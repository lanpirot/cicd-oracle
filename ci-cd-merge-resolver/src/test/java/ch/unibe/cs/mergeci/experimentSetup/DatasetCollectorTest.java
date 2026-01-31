package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class DatasetCollectorTest {

    @Test
    void collectDataset() throws Exception {
        DatasetCollector collector = new DatasetCollector(
                AppConfig.TEST_RESOURCE_DIR.getPath()+"/jackson-databind",
                AppConfig.TMP_DIR.getAbsolutePath(),
                2
        );

        collector.collectDataset(new File(AppConfig.TEST_DATASET_TEMP_DIR+"jackson-databind-dataset-test.xlsx"));
    }
}