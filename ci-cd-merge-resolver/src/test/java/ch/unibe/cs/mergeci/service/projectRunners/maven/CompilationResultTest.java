package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.BaseTest;
import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class CompilationResultTest extends BaseTest {
    @Test
    void test() throws IOException {
        Path file = AppConfig.TEST_RESOURCE_DIR.resolve("compilation-result_2.txt");

        // Verify test resource exists
        assertTrue(Files.exists(file), "Test resource file should exist: " + file);

        CompilationResult compilationResult = new CompilationResult(file);

        System.out.println(compilationResult);

        // Verify compilation result was parsed
        assertNotNull(compilationResult, "CompilationResult should not be null");
        assertTrue(compilationResult.getTotalTime() >= 0, "Total time should be non-negative");
    }


}