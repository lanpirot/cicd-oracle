package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

class TestTotalTest {

    @Test
    void outputResult() throws IOException {
        TestTotal testTotal = new TestTotal(new File(AppConfig.TEST_REPO_DIR, "jitwatch"));
        System.out.println(testTotal);
        testTotal = new TestTotal(new File(AppConfig.TEST_REPO_DIR, "zemberek-nlp"));
        System.out.println(testTotal);
        testTotal = new TestTotal(new File(AppConfig.TEST_REPO_DIR, "jackson-databind"));
        System.out.println(testTotal);
    }
}