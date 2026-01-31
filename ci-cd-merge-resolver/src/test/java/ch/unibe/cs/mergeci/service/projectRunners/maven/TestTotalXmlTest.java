package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class TestTotalXmlTest {

    @Test
    void getRunNum() throws Exception {
        TestTotalXml testTotal = new TestTotalXml(new File(AppConfig.TEST_RESOURCE_DIR.getPath(), "/jackson-databind"));
        System.out.println(testTotal);
    }
}