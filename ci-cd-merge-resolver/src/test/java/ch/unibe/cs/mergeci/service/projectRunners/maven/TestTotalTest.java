package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

public class TestTotalTest {

    @Test
    void outputResult() throws IOException {
        TestTotal testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jitwatch).toFile());
        System.out.println(testTotal);
        testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.zembereknlp).toFile());
        System.out.println(testTotal);
        testTotal = new TestTotal(AppConfig.TEST_REPO_DIR.resolve(AppConfig.jacksonDatabind).toFile());
        System.out.println(testTotal);
    }
}