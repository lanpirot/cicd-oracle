package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TestTotalTest {

    @Test
    void outputResult() throws IOException {
        TestTotal testTotal = new TestTotal(new File(AppConfig.TEST_RESOURCE_DIR.getPath(), "/jitwatch"));
        System.out.println(testTotal);
        testTotal = new TestTotal(new File(AppConfig.TEST_RESOURCE_DIR.getPath(), "/zemberek-nlp"));
        System.out.println(testTotal);
        testTotal = new TestTotal(new File(AppConfig.TEST_RESOURCE_DIR.getPath(), "/jackson-databind"));
        System.out.println(testTotal);
    }
}