package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.util.FileUtils;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.*;

class RepoCollectorTest {

    @Test
    void processExcel() throws Exception {
        RepoCollector repoCollector = new RepoCollector(AppConfig.REPO_DIR.getAbsolutePath(),AppConfig.TMP_DIR.getAbsolutePath());
        repoCollector.processExcel(AppConfig.INPUT_PROJECT_XLSX_TEST);
    }
}