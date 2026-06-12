package ch.unibe.cs.mergeci.runner;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Variant "generator" that does not generate anything: it replays pre-computed
 * competitor candidates from {@code competition/candidates/<tool>/<project>__<mergeCommit>/cand_k/}
 * directories. Each candidate is a set of fully resolved files at repo-relative paths.
 *
 * <p>{@link VariantBuildContext} detects this type and asks for whole-file candidates via
 * {@link #nextCandidateFiles} instead of per-chunk pattern assignments — the rest of the
 * pipeline (budgets, overlayFS, maven-hook early-abort, timeouts, result schema) is unchanged.
 *
 * <p>{@link #emittedKs()} records which candidate k-rank was emitted in which order, so the
 * caller can map {@code variantIndex} (1-based, assigned in emission order by the execution
 * engine) back to the candidate's {@code meta.json} entry.
 */
public class ExternalCandidateGenerator implements IVariantGenerator {

    /** One candidate: its k-rank and {relative path → file content}. */
    public record CandidateFiles(int k, Map<String, String> files) {}

    private final Path mergeDir;
    private final ExternalCandidateMeta meta;
    private final List<Integer> emittedKs = new ArrayList<>();
    private int cursor = 0;

    public ExternalCandidateGenerator(Path mergeDir, ExternalCandidateMeta meta) {
        this.mergeDir = mergeDir;
        this.meta = meta;
    }

    /**
     * Return the next scoreable candidate, or empty when exhausted.
     * Candidates are walked in ascending k order (as listed in meta.json); entries with
     * no {@code cand_k/} directory or not covering every conflicted file are skipped with
     * a console note. {@code tool_failed} is NOT a skip criterion: best-effort candidates
     * keep that flag when the tool crashed (their content is then the ours-side fallback)
     * and must still be scored — the failure itself is carried as metadata.
     *
     * @param requiredPaths repo-relative paths of all conflicted files; every candidate
     *                      must provide a resolution for each of them
     */
    public Optional<CandidateFiles> nextCandidateFiles(Set<String> requiredPaths) {
        List<ExternalCandidateMeta.Candidate> candidates =
                meta.candidates() == null ? List.of() : meta.candidates();
        while (cursor < candidates.size()) {
            ExternalCandidateMeta.Candidate cand = candidates.get(cursor++);
            Path candDir = mergeDir.resolve("cand_" + cand.k());
            if (!Files.isDirectory(candDir)) {
                System.err.printf("  [external] cand_%d listed in meta.json but directory missing — skipped%n", cand.k());
                continue;
            }
            Map<String, String> files;
            try {
                files = readCandidateFiles(candDir);
            } catch (IOException e) {
                System.err.printf("  [external] cand_%d unreadable (%s) — skipped%n", cand.k(), e.getMessage());
                continue;
            }
            List<String> missing = requiredPaths.stream().filter(p -> !files.containsKey(p)).toList();
            if (!missing.isEmpty()) {
                System.err.printf("  [external] cand_%d does not resolve %s — skipped%n", cand.k(), missing);
                continue;
            }
            emittedKs.add(cand.k());
            return Optional.of(new CandidateFiles(cand.k(), files));
        }
        return Optional.empty();
    }

    /** Candidate k-ranks in emission order; index {@code variantIndex - 1} → k. */
    public List<Integer> emittedKs() {
        return emittedKs;
    }

    public ExternalCandidateMeta getMeta() {
        return meta;
    }

    private static Map<String, String> readCandidateFiles(Path candDir) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(candDir)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = candDir.relativize(f).toString();
                result.put(rel, new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    /** External candidates are whole files, not per-chunk pattern assignments. */
    @Override
    public Optional<List<String>> nextVariant() {
        throw new UnsupportedOperationException(
                "ExternalCandidateGenerator provides whole-file candidates via nextCandidateFiles()");
    }
}
