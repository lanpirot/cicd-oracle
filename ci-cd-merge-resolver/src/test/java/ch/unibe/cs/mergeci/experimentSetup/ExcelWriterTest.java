package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;

class ExcelWriterTest {

    @Test
    void filterDatasetsByConflictingFiles() throws IOException {
        ExcelWriter.filterDatasetsByConflictingFiles(AppConfig.TEST_DATASET_DIR);
    }
}