package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Handles writing merge analysis results to JSON files.
 * Each merge is written to its own file: {@code modeDir/<mergeCommit>.json}.
 */
public class JsonResultWriter {

    private final ObjectMapper objectMapper;

    public JsonResultWriter() {
        this.objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    /**
     * Write a single merge result to {@code modeDir/<mergeCommit>.json}.
     *
     * @param merge    The merge result to write
     * @param modeDir  Directory for the current experiment mode
     * @throws IOException if writing fails
     */
    public void writeResult(MergeOutputJSON merge, Path modeDir) throws IOException {
        modeDir.toFile().mkdirs();
        Path outputPath = modeDir.resolve(merge.getMergeCommit() + AppConfig.JSON);
        objectMapper.writeValue(outputPath.toFile(), merge);
    }
}
