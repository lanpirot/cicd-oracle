package ch.unibe.cs.mergeci.experimentSetup.evaluationCollection;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

class CompilationResultJsonTest {

    @Test
    void makeAnalysis() throws Exception {
        File dataset = new File("dataset-test.xlsx");
        File output = new File("dataset-test-otuput.json");
        CompilationResultJson.makeAnalysis(dataset,
                "src\\test\\resources\\test-merge-projects\\jackson-databind",
                output);
    }
}