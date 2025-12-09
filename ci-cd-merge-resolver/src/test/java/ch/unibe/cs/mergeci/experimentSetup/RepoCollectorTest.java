package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.util.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class RepoCollectorTest {

    @Test
    void processExcel() throws Exception {
        RepoCollector repoCollector = new RepoCollector("cloneDir","tempDir", 300, 400);
        repoCollector.processExcel(new File("projects_Java_desc-stars-1000.xlsx"));
    }
}