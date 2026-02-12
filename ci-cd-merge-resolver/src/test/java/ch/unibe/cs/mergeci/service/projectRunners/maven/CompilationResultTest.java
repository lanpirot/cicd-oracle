package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

public class CompilationResultTest {
    @Test
    void test() throws IOException {
        Path file = AppConfig.TEST_RESOURCE_DIR.resolve("compilation-result_2.txt");
        CompilationResult compilationResult = new CompilationResult(file);

        System.out.println(compilationResult);

    }


}