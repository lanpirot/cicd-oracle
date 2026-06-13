package ch.unibe.cs.mergeci.experiment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Per-merge dedup index: maps SHA-256 fingerprints of resolved candidate file sets
 * to stored variant result nodes, so byte-identical candidates across tools or runs
 * can skip a rebuild and copy scores instead.
 *
 * <p>Index files live at {@code ~/data/bruteforcemerge/rq3/dedup_index/<mergeCommit>.json}.
 * Each file is a JSON object mapping fingerprint → stored result node containing
 * {@code mode}, {@code variantIndex}, {@code ownExecutionSeconds}, {@code timedOut},
 * {@code compilationResult}, and {@code testResults}.
 *
 * <p>The fingerprint is SHA-256 over sorted {@code (relPath + UTF-8 fileBytes)} pairs —
 * byte-exact, no normalization, so the equivalence claim is airtight (MergeGen's
 * whitespace-renormalized output will rarely collide with others; that is acceptable).
 *
 * <p>First-writer-wins on insert: once a fingerprint is recorded, subsequent calls to
 * {@link #insert} for the same fingerprint are silent no-ops. This makes byte-identical
 * candidates across tools score identically — more correct for comparison than independent
 * noisy reruns (cf. greenmail 70–96/112 across reruns).
 */
public class CandidateDedupIndex {

    private final Path indexDir;
    private final ObjectMapper mapper = new ObjectMapper();
    /** Per-merge in-memory cache. Loaded on first access, updated on insert. */
    private final Map<String, Map<String, ObjectNode>> cache = new LinkedHashMap<>();

    public CandidateDedupIndex(Path indexDir) throws IOException {
        this.indexDir = indexDir;
        Files.createDirectories(indexDir);
    }

    // ── fingerprinting ───────────────────────────────────────────────────────

    /**
     * SHA-256 over sorted {@code (relPath, UTF-8 fileBytes)} pairs.
     * Byte-exact only — no whitespace normalization.
     */
    public static String computeFingerprint(Map<String, String> files) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            files.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> {
                        digest.update(e.getKey().getBytes(StandardCharsets.UTF_8));
                        digest.update(e.getValue().getBytes(StandardCharsets.UTF_8));
                    });
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 unavailable", e);
        }
    }

    /**
     * Read all files under {@code candDir} into a {@code relPath → content} map.
     * Returns an empty map if the directory does not exist.
     */
    public static Map<String, String> readCandidateFiles(Path candDir) throws IOException {
        if (!Files.isDirectory(candDir)) return Map.of();
        Map<String, String> result = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(candDir)) {
            for (Path f : walk.filter(Files::isRegularFile).sorted().toList()) {
                String rel = candDir.relativize(f).toString();
                result.put(rel, new String(Files.readAllBytes(f), StandardCharsets.UTF_8));
            }
        }
        return result;
    }

    // ── lookup / insert ──────────────────────────────────────────────────────

    /**
     * Return the stored variant result node for this fingerprint, or {@code null} on miss.
     */
    public ObjectNode lookup(String mergeCommit, String fingerprint) throws IOException {
        return loadIndex(mergeCommit).get(fingerprint);
    }

    /**
     * Store a variant result node for a fingerprint. Persists atomically.
     * No-ops silently when the fingerprint is already present (first-writer wins).
     */
    public void insert(String mergeCommit, String fingerprint, ObjectNode resultNode) throws IOException {
        Map<String, ObjectNode> idx = loadIndex(mergeCommit);
        if (idx.containsKey(fingerprint)) return;
        idx.put(fingerprint, resultNode);
        persist(mergeCommit, idx);
    }

    // ── internals ────────────────────────────────────────────────────────────

    private Map<String, ObjectNode> loadIndex(String mergeCommit) throws IOException {
        if (!cache.containsKey(mergeCommit)) {
            Path file = indexDir.resolve(mergeCommit + ".json");
            if (Files.exists(file)) {
                ObjectNode root = (ObjectNode) mapper.readTree(file.toFile());
                Map<String, ObjectNode> m = new LinkedHashMap<>();
                root.fields().forEachRemaining(e -> m.put(e.getKey(), (ObjectNode) e.getValue()));
                cache.put(mergeCommit, m);
            } else {
                cache.put(mergeCommit, new LinkedHashMap<>());
            }
        }
        return cache.get(mergeCommit);
    }

    private void persist(String mergeCommit, Map<String, ObjectNode> idx) throws IOException {
        Path file = indexDir.resolve(mergeCommit + ".json");
        ObjectNode root = mapper.createObjectNode();
        idx.forEach(root::set);
        Path tmp = Files.createTempFile(indexDir, mergeCommit, ".json.tmp");
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
    }
}
