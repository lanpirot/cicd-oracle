package ch.unibe.cs.mergeci.runner.maven;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

public class TestTotalTest extends BaseTest {

    @Test
    void outputResult() throws IOException {
        TestTotal testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jitwatch).toFile());
        System.out.println(testTotal);
        assertNotNull(testTotal, "TestTotal should not be null for jitwatch");

        testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.zembereknlp).toFile());
        System.out.println(testTotal);
        assertNotNull(testTotal, "TestTotal should not be null for zembereknlp");

        testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind).toFile());
        System.out.println(testTotal);
        assertNotNull(testTotal, "TestTotal should not be null for jacksonDatabind");
    }
}
