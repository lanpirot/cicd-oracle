package ch.unibe.cs.mergeci.runner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalCandidateGeneratorTest {

    @TempDir
    Path mergeDir;

    private static final String META_JSON = """
            {
              "tool": "jdime",
              "tool_version": "0.5-develop",
              "tool_config": "modes=['semistructured', 'structured']",
              "merge_commit": "047fb51ee2050f75b5f71322832f97f1f87dfa03",
              "project_name": "yegor256/cactoos",
              "total_cli_chunks": 1,
              "total_jgit_chunks": 1,
              "compute_seconds": 0.729,
              "candidates": [
                {"k": 0, "mode": "semistructured", "chunks_resolved": 1, "chunks_total": 1,
                 "strict": true, "best_effort": false, "tool_failed": false, "failure_reason": null},
                {"k": 1, "mode": "semistructured", "chunks_resolved": 0, "chunks_total": 1,
                 "strict": false, "best_effort": true, "tool_failed": true, "failure_reason": "exit 210"},
                {"k": 2, "mode": "structured", "chunks_resolved": 1, "chunks_total": 1,
                 "strict": true, "best_effort": false, "tool_failed": false, "failure_reason": null}
              ]
            }
            """;

    private ExternalCandidateGenerator generatorWith(String... candDirsWithFile) throws IOException {
        Files.writeString(mergeDir.resolve("meta.json"), META_JSON);
        for (String candDir : candDirsWithFile) {
            Path file = mergeDir.resolve(candDir).resolve("src/main/java/Foo.java");
            Files.createDirectories(file.getParent());
            Files.writeString(file, "class Foo { /* " + candDir + " */ }\n");
        }
        return new ExternalCandidateGenerator(mergeDir, ExternalCandidateMeta.read(mergeDir));
    }

    @Test
    void metaParsesAllFields() throws IOException {
        Files.writeString(mergeDir.resolve("meta.json"), META_JSON);
        ExternalCandidateMeta meta = ExternalCandidateMeta.read(mergeDir);

        assertEquals("jdime", meta.tool());
        assertEquals("047fb51ee2050f75b5f71322832f97f1f87dfa03", meta.mergeCommit());
        assertEquals(0.729, meta.computeSeconds(), 1e-9);
        assertEquals(3, meta.candidates().size());
        assertEquals("structured", meta.candidateForK(2).mode());
        assertTrue(meta.candidateForK(2).strict());
        assertTrue(meta.candidateForK(1).toolFailed());
    }

    @Test
    void emitsCandidatesInOrderSkippingMissingDirs() throws IOException {
        ExternalCandidateGenerator gen = generatorWith("cand_0", "cand_2");
        Set<String> required = Set.of("src/main/java/Foo.java");

        Optional<ExternalCandidateGenerator.CandidateFiles> first = gen.nextCandidateFiles(required);
        Optional<ExternalCandidateGenerator.CandidateFiles> second = gen.nextCandidateFiles(required);
        Optional<ExternalCandidateGenerator.CandidateFiles> third = gen.nextCandidateFiles(required);

        assertEquals(0, first.orElseThrow().k());
        assertEquals(2, second.orElseThrow().k()); // k=1 has no cand_1/ directory
        assertTrue(third.isEmpty());
        assertEquals(List.of(0, 2), gen.emittedKs());
        assertTrue(first.get().files().get("src/main/java/Foo.java").contains("cand_0"));
    }

    @Test
    void toolFailedCandidateWithDirIsStillEmitted() throws IOException {
        // Best-effort candidates keep tool_failed=true when the tool crashed; their
        // ours-fallback content must still be scored (the crash is metadata, not a skip).
        ExternalCandidateGenerator gen = generatorWith("cand_0", "cand_1", "cand_2");
        Set<String> required = Set.of("src/main/java/Foo.java");

        gen.nextCandidateFiles(required);
        Optional<ExternalCandidateGenerator.CandidateFiles> second = gen.nextCandidateFiles(required);

        assertEquals(1, second.orElseThrow().k());
        assertEquals(List.of(0, 1), gen.emittedKs());
    }

    @Test
    void skipsCandidateMissingAConflictedFile() throws IOException {
        ExternalCandidateGenerator gen = generatorWith("cand_0", "cand_2");
        // cand dirs only contain Foo.java; requiring pom.xml as well disqualifies both
        Set<String> required = Set.of("src/main/java/Foo.java", "pom.xml");

        assertTrue(gen.nextCandidateFiles(required).isEmpty());
        assertTrue(gen.emittedKs().isEmpty());
    }

    @Test
    void patternInterfaceIsUnsupported() throws IOException {
        ExternalCandidateGenerator gen = generatorWith("cand_0");
        assertThrows(UnsupportedOperationException.class, gen::nextVariant);
    }
}
