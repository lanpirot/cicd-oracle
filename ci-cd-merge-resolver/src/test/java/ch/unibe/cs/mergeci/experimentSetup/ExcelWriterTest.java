package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ExcelWriterTest {

    @Test
    void filterDatasetsByConflictingFiles() throws IOException {
        ExcelWriter.filterDatasetsByConflictingFiles(AppConfig.TEST_DATASET_TEMP_DIR);
    }
}