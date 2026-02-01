package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

class CompilationResultTest {
    @Test
    void test() throws IOException {
        File file = new File(AppConfig.TEST_RESOURCE_DIR,"compilation-result_2.txt");
        CompilationResult compilationResult = new CompilationResult(file);

        System.out.println(compilationResult);

    }


}