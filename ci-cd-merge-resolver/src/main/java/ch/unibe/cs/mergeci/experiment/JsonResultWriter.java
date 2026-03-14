package ch.unibe.cs.mergeci.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Handles writing merge analysis results to JSON files.
 * Encapsulates the JSON serialization logic for experiment results.
 */
public class JsonResultWriter {

    private final ObjectMapper objectMapper;

    public JsonResultWriter() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Write merge analysis results to a JSON file.
     *
     * @param projectName Name of the project being analyzed
     * @param merges List of merge results to write
     * @param outputPath Path where the JSON file should be written
     * @throws IOException if writing fails
     */
    public void writeResults(String projectName, List<MergeOutputJSON> merges, Path outputPath) throws IOException {
        AllMergesJSON allMerges = new AllMergesJSON();
        allMerges.setProjectName(projectName);
        allMerges.setMerges(merges);

        objectMapper.writeValue(outputPath.toFile(), allMerges);
    }
}
