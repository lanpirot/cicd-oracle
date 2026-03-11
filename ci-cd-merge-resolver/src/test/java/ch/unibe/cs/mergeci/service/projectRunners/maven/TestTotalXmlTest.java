package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TestTotalXmlTest extends BaseTest {

    @Test
    void getRunNum() throws Exception {
        TestTotalXml testTotal = new TestTotalXml(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind).toFile());
        System.out.println(testTotal);

        // Verify test total was created and has valid data
        assertNotNull(testTotal, "TestTotalXml should not be null");
        assertTrue(testTotal.getRunNum() >= 0, "Run number should be non-negative");
    }
}