package ch.unibe.cs.mergeci.experimentSetup;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

public class RepoCollectorTest {

    @Test
    void processExcel() throws Exception {
        RepoCollector repoCollector = new RepoCollector(AppConfig.REPO_DIR.getAbsolutePath(), AppConfig.TMP_DIR.getAbsolutePath());
        repoCollector.processExcel(AppConfig.TEST_INPUT_PROJECT_XLSX);
    }
}