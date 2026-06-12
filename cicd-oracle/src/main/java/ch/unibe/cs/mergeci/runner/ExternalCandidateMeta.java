package ch.unibe.cs.mergeci.runner;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Parsed {@code meta.json} of one competitor-candidate merge directory
 * ({@code competition/candidates/<tool>/<project>__<mergeCommit>/meta.json}).
 * Schema is defined in {@code competition/harness/candidates.py}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalCandidateMeta(
        @JsonProperty("tool") String tool,
        @JsonProperty("tool_version") String toolVersion,
        @JsonProperty("tool_config") String toolConfig,
        @JsonProperty("merge_commit") String mergeCommit,
        @JsonProperty("project_name") String projectName,
        @JsonProperty("total_cli_chunks") int totalCliChunks,
        @JsonProperty("total_jgit_chunks") int totalJgitChunks,
        @JsonProperty("compute_seconds") Double computeSeconds,
        @JsonProperty("candidates") List<Candidate> candidates) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Candidate(
            @JsonProperty("k") int k,
            @JsonProperty("mode") String mode,
            @JsonProperty("chunks_resolved") Integer chunksResolved,
            @JsonProperty("chunks_total") Integer chunksTotal,
            @JsonProperty("strict") Boolean strict,
            @JsonProperty("best_effort") Boolean bestEffort,
            @JsonProperty("tool_failed") Boolean toolFailed,
            @JsonProperty("failure_reason") String failureReason) {
    }

    /** Load and parse {@code <mergeDir>/meta.json}. */
    public static ExternalCandidateMeta read(Path mergeDir) throws IOException {
        return new ObjectMapper().readValue(mergeDir.resolve("meta.json").toFile(), ExternalCandidateMeta.class);
    }

    /** Find the candidate entry with the given k-rank, or null. */
    public Candidate candidateForK(int k) {
        if (candidates == null) return null;
        for (Candidate c : candidates) {
            if (c.k() == k) return c;
        }
        return null;
    }
}
