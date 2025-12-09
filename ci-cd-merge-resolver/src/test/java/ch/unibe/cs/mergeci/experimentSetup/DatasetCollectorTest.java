package ch.unibe.cs.mergeci.experimentSetup;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class DatasetCollectorTest {

    @Test
    void collectDataset() throws Exception {
        DatasetCollector collector = new DatasetCollector(
                "src/test/resources/test-merge-projects/jackson-databind",
                "dataset_temp",
                2
        );

        collector.collectDataset(new File("jackson-databind-dataset_wcache.xlsx"));
    }
}