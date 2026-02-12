package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;

public class TestTotalXmlTest {

    @Test
    void getRunNum() throws Exception {
        TestTotalXml testTotal = new TestTotalXml(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind).toFile());
        System.out.println(testTotal);
    }
}