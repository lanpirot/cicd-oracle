package ch.unibe.cs.mergeci.service.projectRunners.maven;

import ch.unibe.cs.mergeci.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CompilationResultTest {
    @Test
    void test() throws IOException {
        File file = new File(AppConfig.TEST_RESOURCE_DIR2.getPath()+"/compilation-result_2.txt");
        CompilationResult compilationResult = new CompilationResult(file);

        System.out.println(compilationResult);

    }


}