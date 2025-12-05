package ch.unibe.cs.mergeci.service.projectRunners.maven;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class TestTotalXmlTest {

    @Test
    void getRunNum() throws Exception {
        TestTotalXml testTotal = new TestTotalXml(new File("src\\test\\resources\\jackson-databind"));
        System.out.println(testTotal);
    }
}