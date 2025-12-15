package ch.unibe.cs.mergeci.experimentSetup;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class ExcelWriterTest {

    @Test
    void filterDatasetsByConflictingFiles() throws IOException {
        ExcelWriter.filterDatasetsByConflictingFiles(new File("experiments/datasets"));
    }
}