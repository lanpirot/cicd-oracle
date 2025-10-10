package ch.unibe.cs.mergeci.service.projectRunners.maven;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class TestResultTest {

    @Test
    void createTestResultFromFile() throws IOException {
        TestResult testResult = TestResult.createTestResultFromFile(new File(
                "C:\\Users\\vova\\Documents\\BernUni\\Semester3\\Thesis\\commons-lang\\target\\surefire-reports\\org.apache.commons.lang3.ArrayUtilsAddTest.txt"));
    }
}