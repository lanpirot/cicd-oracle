package ch.unibe.cs.mergeci.service.projectRunners.maven;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class CompilationResultTest {
    @Test
    void test() throws IOException {
        File file = new File("src/test/resources/test-files/compilation-result_3.txt");
        CompilationResult compilationResult = new CompilationResult(file);

        System.out.println(compilationResult);


    }


}