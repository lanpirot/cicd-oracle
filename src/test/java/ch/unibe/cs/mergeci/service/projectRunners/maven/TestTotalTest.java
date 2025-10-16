package ch.unibe.cs.mergeci.service.projectRunners.maven;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class TestTotalTest {

    @Test
    void outputResult() throws IOException {
        TestTotal testTotal = new TestTotal(new File("src\\test\\resources\\test-merge-projects\\dozer"));
//        TestTotal testTotal = new TestTotal(new File("temp\\airlift_1"));
        testTotal.outputResult();
    }
}