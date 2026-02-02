package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;

class TestTotalXmlTest {

    @Test
    void getRunNum() throws Exception {
        TestTotalXml testTotal = new TestTotalXml(new File(AppConfig.TEST_REPO_DIR.getPath(), AppConfig.jacksonDatabind));
        System.out.println(testTotal);
    }
}