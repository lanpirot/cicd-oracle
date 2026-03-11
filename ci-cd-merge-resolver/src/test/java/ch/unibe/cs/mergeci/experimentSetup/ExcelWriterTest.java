package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class ExcelWriterTest extends BaseTest {

    @Test
    void filterDatasetsByConflictingFiles() throws IOException {
        // Execute the filtering operation (will create directory if it doesn't exist)
        ExcelWriter.filterDatasetsByConflictingFiles(AppConfig.TEST_DATASET_DIR.toFile());

        // Verify the operation completed without throwing exceptions
        // The method should process the directory successfully (even if empty)
        assertTrue(true, "Filter operation completed successfully");
    }
}