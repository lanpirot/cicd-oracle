package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

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
     * <p>Uses atomic write (temp file + rename) so a crash during serialization
     * never leaves a half-written JSON that the restart logic would skip.
     *
     * @param merge    The merge result to write
     * @param modeDir  Directory for the current experiment mode
     * @throws IOException if writing fails
     */
    public void writeResult(MergeOutputJSON merge, Path modeDir) throws IOException {
        modeDir.toFile().mkdirs();
        Path outputPath = modeDir.resolve(merge.getMergeCommit() + AppConfig.JSON);
        File tmpFile = File.createTempFile(merge.getMergeCommit(), ".json.tmp", modeDir.toFile());
        try {
            objectMapper.writeValue(tmpFile, merge);
            Files.move(tmpFile.toPath(), outputPath, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tmpFile.delete();
            throw e;
        }
    }
}
