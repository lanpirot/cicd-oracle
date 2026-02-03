package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

public class TestTotalTest {

    @Test
    void outputResult() throws IOException {
        TestTotal testTotal = new TestTotal(new File(AppConfig.TEST_REPO_DIR, AppConfig.jitwatch));
        System.out.println(testTotal);
        testTotal = new TestTotal(new File(AppConfig.TEST_REPO_DIR, AppConfig.zembereknlp));
        System.out.println(testTotal);
        testTotal = new TestTotal(new File(AppConfig.TEST_REPO_DIR, AppConfig.jacksonDatabind));
        System.out.println(testTotal);
    }
}