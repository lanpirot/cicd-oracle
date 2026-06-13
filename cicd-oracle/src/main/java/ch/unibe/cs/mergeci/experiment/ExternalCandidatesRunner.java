package ch.unibe.cs.mergeci.experiment;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.runner.ExternalCandidateGenerator;
import ch.unibe.cs.mergeci.runner.ExternalCandidateGeneratorFactory;
import ch.unibe.cs.mergeci.runner.ExternalCandidateMeta;
import ch.unibe.cs.mergeci.runner.MavenExecutionFactory;
import ch.unibe.cs.mergeci.runner.OverlayMount;
import ch.unibe.cs.mergeci.runner.maven.MavenCommandResolver;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.RepositoryManager;
import ch.unibe.cs.mergeci.util.Utility;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * External-candidates mode: scores pre-computed competitor candidates
 * ({@code competition/candidates/<tool>/<project>__<mergeCommit>/cand_k/})
 * through the exact same build/test oracle as RQ3 variants.
 *
 * <p>Per merge, the candidates replace generated variants (via
 * {@link ExternalCandidateGenerator}); budgets are sized from the stored RQ3
 * {@code human_baseline} JSON, the execution flags mirror the RQ3 best mode
 * ({@code rq3BestMode}, default cache_parallel), and results are written as
 * {@code ~/data/bruteforcemerge/rq3/<tool>/<mergeCommit>.json} with
 * {@code mode: "<tool>"} in the standard schema, annotated with the
 * candidate meta.json fields (computeSeconds, strict/best-effort, k-rank).
 *
 * <p><b>Dedup index</b> ({@link CandidateDedupIndex}): before building a merge,
 * the runner fingerprints all its candidates (SHA-256 over sorted resolved-file
 * content). When every candidate fingerprint is already in the per-merge index at
 * {@code ~/data/bruteforcemerge/rq3/dedup_index/}, the build is skipped entirely:
 * scores are copied from the index and a synthetic result JSON is written (same
 * schema, with added {@code dedupOfMode}/{@code dedupOfVariantIndex} provenance).
 * After every successful build, the newly scored variants are inserted into the
 * index for future dedup. First-writer-wins; deduped entries score identically
 * to the original — more correct than independent noisy reruns.
 *
 * <p>System properties:
 * <ul>
 *   <li>{@code tool} — tool subdirectory under the candidates root (default {@code jdime})</li>
 *   <li>{@code candidatesDir} — candidates root; default: walk up from cwd looking for
 *       {@code competition/candidates}</li>
 *   <li>{@code mergeCommit} — optional prefix filter to score a single merge</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 * java -Dtool=jdime -DmergeCommit=047fb51e -DoverlayTmpDir=/dev/shm \
 *      -cp "target/*:target/lib/*" ch.unibe.cs.mergeci.experiment.ExternalCandidatesRunner
 * </pre>
 */
public class ExternalCandidatesRunner {

    private final RepositoryManager repoManager = new RepositoryManager(AppConfig.REPO_DIR);
    private final ObjectMapper mapper = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        new ExternalCandidatesRunner().run();
    }

    public void run() throws Exception {
        String tool = System.getProperty("tool", "jdime").trim();
        Path toolDir = resolveCandidatesRoot().resolve(tool);
        if (!Files.isDirectory(toolDir)) {
            throw new IOException("No candidate directory for tool '" + tool + "' at " + toolDir);
        }
        String onlyMerge = System.getProperty("mergeCommit", "").trim();

        Utility.Experiments flagsSource = bestModeFlags();
        Path modeDir = AppConfig.RQ3_VARIANT_EXPERIMENT_DIR.resolve(tool);
        Path humanBaselineDir = AppConfig.RQ3_VARIANT_EXPERIMENT_DIR.resolve("human_baseline");
        if (!Files.isDirectory(humanBaselineDir)) {
            throw new IOException("RQ3 human_baseline directory not found at " + humanBaselineDir
                    + " — external candidates need the stored baselines for budget sizing");
        }

        CandidateDedupIndex dedupIndex = new CandidateDedupIndex(
                AppConfig.RQ3_VARIANT_EXPERIMENT_DIR.resolve("dedup_index"));

        Map<String, DatasetReader.MergeInfo> mergesByCommit = loadMergeIndex();

        List<Path> mergeDirs;
        try (var stream = Files.list(toolDir)) {
            mergeDirs = stream.filter(d -> Files.exists(d.resolve("meta.json"))).sorted().toList();
        }

        System.out.println("================================================================================");
        System.out.println("CI/CD Merge Resolver - External Candidates Phase");
        System.out.printf("Tool: %s | Candidate merges: %d | Execution flags: %s (parallel=%s, cache=%s)%n",
                tool, mergeDirs.size(), flagsSource.getName(), flagsSource.isParallel(), flagsSource.isCache());
        System.out.printf("Output dir: %s%n", modeDir);
        System.out.println("================================================================================");

        int processed = 0, deduped = 0, skipped = 0;
        for (Path mergeDir : mergeDirs) {
            ExternalCandidateMeta meta = ExternalCandidateMeta.read(mergeDir);
            String mergeCommit = meta.mergeCommit();
            if (!onlyMerge.isEmpty() && !mergeCommit.startsWith(onlyMerge)) continue;

            System.out.printf("%n── %s  [%s %s] ──%n", tool, meta.projectName(),
                    mergeCommit.substring(0, Math.min(8, mergeCommit.length())));

            DatasetReader.MergeInfo info = mergesByCommit.get(mergeCommit);
            if (info == null) {
                System.err.println("  SKIPPED: merge not found in " + AppConfig.MAVEN_CONFLICTS_CSV);
                skipped++;
                continue;
            }
            Path baselineJson = humanBaselineDir.resolve(mergeCommit + AppConfig.JSON);
            if (!baselineJson.toFile().exists()) {
                System.err.println("  SKIPPED: no RQ3 human_baseline JSON (merge was never scored by RQ3)");
                skipped++;
                continue;
            }

            Path resultJson = modeDir.resolve(mergeCommit + AppConfig.JSON);

            // Skip metas (tool produced nothing: no_repo / no Java conflict files / …)
            // carry an empty candidates list — nothing to score, coverage is counted
            // from meta.json by the WP8 analysis.
            if (meta.candidates() == null || meta.candidates().isEmpty()) {
                System.out.println("  SKIP (skip meta — tool produced no candidates)");
                skipped++;
                continue;
            }

            // ── Dedup: fingerprint all candidates ────────────────────────────
            Map<Integer, String> kToFingerprint = computeFingerprints(mergeDir, meta);
            if (kToFingerprint.isEmpty()) {
                System.out.println("  SKIP (no scoreable candidate directories)");
                skipped++;
                continue;
            }

            // Resume: result already written → re-insert its fingerprints into the
            // index (covers entries lost to a crash between JSON write and insert).
            if (isProcessed(resultJson)) {
                updateDedupIndex(dedupIndex, mergeCommit, tool, kToFingerprint, resultJson);
                System.out.println("  SKIP (already processed; dedup index refreshed)");
                skipped++;
                continue;
            }

            Map<Integer, ObjectNode> kToHit = new LinkedHashMap<>();
            for (Map.Entry<Integer, String> e : kToFingerprint.entrySet()) {
                ObjectNode hit = dedupIndex.lookup(mergeCommit, e.getValue());
                if (hit != null) kToHit.put(e.getKey(), hit);
            }

            if (kToHit.size() == kToFingerprint.size()) {
                synthesizeDedupResult(resultJson, mergeCommit, tool, meta,
                        kToFingerprint, kToHit, baselineJson);
                deduped++;
                continue;
            }

            // ── Normal build path ────────────────────────────────────────────
            ExternalCandidateGeneratorFactory factory = new ExternalCandidateGeneratorFactory(mergeDir, meta);
            try {
                Path repoPath = repoManager.getRepositoryPath(info.getProjectName(), info.getRemoteUrl());
                String[] parents = GitUtils.getParentCommits(repoPath, mergeCommit);
                info.setParent1(parents[0]);
                info.setParent2(parents[1]);

                ResolutionVariantRunner.makeAnalysisByMergeList(
                        List.of(info), info.getProjectName(), repoPath, modeDir, humanBaselineDir,
                        flagsSource.isParallel(), flagsSource.isCache(), false,
                        AppConfig.TMP_DIR, tool,
                        factory, null,
                        false /* score every candidate, never stop on perfect */,
                        null /* no attempted_merges.csv pollution */,
                        false /* never write back into shared RQ3 baselines */);
                processed++;
            } catch (Exception e) {
                System.err.println("  FAILED: " + e.getMessage());
                skipped++;
                continue;
            } finally {
                new MavenCommandResolver(AppConfig.USE_MAVEN_DAEMON).stopDaemons();
                FileUtils.deleteQuietly(AppConfig.SHARED_CACHE_DIR.toFile());
                OverlayMount.cleanupStaleMounts(AppConfig.OVERLAY_TMP_DIR.resolve("projects"));
                OverlayMount.cleanupStaleMounts(MavenExecutionFactory.M2_OVERLAY_DIR);
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
            }

            annotateResult(resultJson, meta, factory.getLastCreated());
            updateDedupIndex(dedupIndex, mergeCommit, tool, kToFingerprint, resultJson);
        }

        System.out.printf("%nExternal candidates finished: %d built, %d deduped, %d skipped.%n",
                processed, deduped, skipped);
    }

    // ── dedup: fingerprint computation ───────────────────────────────────────

    /** Compute SHA-256 fingerprints for all candidates of a merge that have a {@code cand_k/} dir. */
    private Map<Integer, String> computeFingerprints(Path mergeDir, ExternalCandidateMeta meta) {
        Map<Integer, String> result = new LinkedHashMap<>();
        if (meta.candidates() == null) return result;
        for (ExternalCandidateMeta.Candidate cand : meta.candidates()) {
            Path candDir = mergeDir.resolve("cand_" + cand.k());
            if (!Files.isDirectory(candDir)) continue;
            try {
                Map<String, String> files = CandidateDedupIndex.readCandidateFiles(candDir);
                if (files.isEmpty()) continue;
                result.put(cand.k(), CandidateDedupIndex.computeFingerprint(files));
            } catch (IOException e) {
                System.err.printf("  [dedup] failed to fingerprint cand_%d: %s%n", cand.k(), e.getMessage());
            }
        }
        return result;
    }

    // ── dedup: synthesize result JSON from index hits ────────────────────────

    /**
     * Write a synthetic result JSON for a merge where every candidate is a dedup hit.
     * Copies the merge metadata from the human_baseline JSON; virtual ownExecutionSeconds
     * are summed to charge against the budget (budgetExhausted is set if exceeded).
     * Dedup provenance fields (dedupOfMode, dedupOfVariantIndex) are added per variant.
     */
    private void synthesizeDedupResult(
            Path resultJson,
            String mergeCommit,
            String tool,
            ExternalCandidateMeta meta,
            Map<Integer, String> kToFingerprint,
            Map<Integer, ObjectNode> kToHit,
            Path baselineJson) throws IOException {

        ObjectNode baseline = (ObjectNode) mapper.readTree(baselineJson.toFile());
        long variantBudgetSeconds = baseline.path("variantBudgetSeconds").asLong(300);

        // Conflict file paths for MANUAL pattern entries (from baseline variants[0].conflictPatterns)
        Set<String> conflictFilePaths = new LinkedHashSet<>();
        JsonNode baselineVariants = baseline.path("variants");
        if (baselineVariants.isArray() && baselineVariants.size() > 0) {
            JsonNode patterns = baselineVariants.get(0).path("conflictPatterns");
            if (patterns.isObject()) {
                patterns.fieldNames().forEachRemaining(conflictFilePaths::add);
            }
        }

        // Build variant entries
        ArrayNode variantsArray = mapper.createArrayNode();
        double cumulativeSeconds = 0;
        boolean budgetExhausted = false;
        int variantIndex = 1;

        List<ExternalCandidateMeta.Candidate> candidates =
                meta.candidates() == null ? List.of() : meta.candidates();
        for (ExternalCandidateMeta.Candidate cand : candidates) {
            String fp = kToFingerprint.get(cand.k());
            if (fp == null) continue;
            ObjectNode hit = kToHit.get(cand.k());
            if (hit == null) continue;

            double own = hit.path("ownExecutionSeconds").asDouble(0);

            // Enforce virtual budget: stop emitting variants once budget is exhausted
            if (cumulativeSeconds >= variantBudgetSeconds) {
                budgetExhausted = true;
                break;
            }

            cumulativeSeconds += own;
            ObjectNode variant = mapper.createObjectNode();
            variant.put("variantIndex", variantIndex++);
            variant.put("isCacheDonor", false);
            variant.put("hadWarmCacheReady", false);
            variant.put("ownExecutionSeconds", own);
            variant.put("totalTimeSinceMergeStartSeconds", cumulativeSeconds);
            variant.put("timedOut", hit.path("timedOut").asBoolean(false));
            if (hit.has("compilationResult")) variant.set("compilationResult", hit.get("compilationResult").deepCopy());
            if (hit.has("testResults")) variant.set("testResults", hit.get("testResults").deepCopy());

            // MANUAL conflictPatterns for each conflict file (same as the build path)
            ObjectNode conflictPatterns = mapper.createObjectNode();
            for (String filePath : conflictFilePaths) {
                ArrayNode patArray = mapper.createArrayNode();
                patArray.add("MANUAL");
                conflictPatterns.set(filePath, patArray);
            }
            variant.set("conflictPatterns", conflictPatterns);

            // Candidate meta fields
            variant.put("candidateK", cand.k());
            if (cand.mode() != null) variant.put("candidateMode", cand.mode());
            if (cand.strict() != null) variant.put("candidateStrict", cand.strict());
            if (cand.bestEffort() != null) variant.put("candidateBestEffort", cand.bestEffort());
            if (cand.chunksResolved() != null) variant.put("candidateChunksResolved", cand.chunksResolved());
            if (cand.chunksTotal() != null) variant.put("candidateChunksTotal", cand.chunksTotal());
            if (cand.toolFailed() != null) variant.put("candidateToolFailed", cand.toolFailed());

            // Dedup provenance
            variant.put("dedupOfMode", hit.path("mode").asText());
            variant.put("dedupOfVariantIndex", hit.path("variantIndex").asInt());

            variantsArray.add(variant);
        }

        // Build full result JSON (metadata from baseline, mode from tool)
        ObjectNode result = mapper.createObjectNode();
        result.put("processed", true);
        result.put("mode", tool);
        copyField(result, "projectName", baseline);
        result.put("mergeCommit", mergeCommit);
        copyField(result, "parent1", baseline);
        copyField(result, "parent2", baseline);
        copyField(result, "numConflictFiles", baseline);
        copyField(result, "numJavaConflictFiles", baseline);
        copyField(result, "numConflictChunks", baseline);
        copyField(result, "isMultiModule", baseline);
        copyField(result, "baselineBroken", baseline);
        copyNullableField(result, "baselineFailureType", baseline);
        copyField(result, "variantsSkipped", baseline);
        copyField(result, "buildFileConflictMarkers", baseline);
        copyField(result, "budgetBasisSeconds", baseline);
        copyField(result, "peakBaselineRamBytes", baseline);
        copyField(result, "baselineDirGrowthBytes", baseline);
        result.put("variantBudgetSeconds", variantBudgetSeconds);
        copyField(result, "threads", baseline);
        long virtualSeconds = (long) Math.ceil(cumulativeSeconds);
        result.put("totalExecutionTime", virtualSeconds);
        result.put("numInFlightVariantsKilled", 0);
        result.put("budgetExhausted", budgetExhausted || cumulativeSeconds > variantBudgetSeconds);
        result.put("variantsExecutionTimeSeconds", virtualSeconds);
        result.set("variants", variantsArray);

        // Top-level candidate meta (from meta.json)
        if (meta.computeSeconds() != null) result.put("candidateComputeSeconds", meta.computeSeconds());
        if (meta.toolVersion() != null) result.put("toolVersion", meta.toolVersion());
        if (meta.toolConfig() != null) result.put("toolConfig", meta.toolConfig());

        // Atomic write, mirroring JsonResultWriter
        Files.createDirectories(resultJson.getParent());
        File tmp = File.createTempFile(mergeCommit, ".json.tmp", resultJson.getParent().toFile());
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmp, result);
            Files.move(tmp.toPath(), resultJson,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }

        System.out.printf("  DEDUP (%d/%d candidates from index, %.1fs virtual budget used)%n",
                variantsArray.size(), kToFingerprint.size(), cumulativeSeconds);
    }

    // ── dedup: update index after a successful build ─────────────────────────

    /**
     * Insert each variant's fingerprint from an annotated result JSON into the dedup index.
     * Maps variant → candidate k via the {@code candidateK} field written by
     * {@link #annotateResult}, so it works both right after a build and on resume
     * (when no live generator exists). Insertion is first-writer-wins, so re-running
     * over already-indexed results is a no-op.
     */
    private void updateDedupIndex(
            CandidateDedupIndex index,
            String mergeCommit,
            String tool,
            Map<Integer, String> kToFingerprint,
            Path resultJson) {
        if (!resultJson.toFile().exists() || kToFingerprint.isEmpty()) return;

        try {
            ObjectNode result = (ObjectNode) mapper.readTree(resultJson.toFile());
            JsonNode variants = result.path("variants");
            if (!variants.isArray()) return;

            for (JsonNode variantNode : variants) {
                JsonNode kNode = variantNode.get("candidateK");
                if (kNode == null || !kNode.canConvertToInt()) continue;
                String fingerprint = kToFingerprint.get(kNode.asInt());
                if (fingerprint == null) continue;

                ObjectNode stored = mapper.createObjectNode();
                stored.put("mode", tool);
                stored.put("variantIndex", variantNode.path("variantIndex").asInt());
                stored.put("ownExecutionSeconds", variantNode.path("ownExecutionSeconds").asDouble(0));
                stored.put("timedOut", variantNode.path("timedOut").asBoolean(false));
                if (variantNode.has("compilationResult"))
                    stored.set("compilationResult", variantNode.get("compilationResult").deepCopy());
                if (variantNode.has("testResults"))
                    stored.set("testResults", variantNode.get("testResults").deepCopy());

                index.insert(mergeCommit, fingerprint, stored);
            }
        } catch (IOException e) {
            System.err.printf("  [dedup] failed to update index for %s: %s%n",
                    mergeCommit.substring(0, Math.min(8, mergeCommit.length())), e.getMessage());
        }
    }

    /** True when the result JSON exists and is complete ({@code processed:true}). */
    private boolean isProcessed(Path resultJson) {
        if (!resultJson.toFile().exists()) return false;
        try {
            return mapper.readTree(resultJson.toFile()).path("processed").asBoolean(false);
        } catch (IOException e) {
            return false;
        }
    }

    // ── result annotation (unchanged) ────────────────────────────────────────

    /**
     * Enrich the freshly written result JSON with the candidate metadata from meta.json.
     * variantIndex is assigned by the execution engine in emission order (1-based), so
     * {@code emittedKs.get(variantIndex - 1)} is the candidate's k-rank.
     *
     * <p>Operates on the raw JSON tree (not the {@link MergeOutputJSON} POJOs): nested
     * result types like {@code CompilationResult} are built from build logs and do not
     * deserialize losslessly, so a POJO round-trip would corrupt module counts.
     */
    private void annotateResult(Path jsonFile, ExternalCandidateMeta meta,
                                ExternalCandidateGenerator generator) throws IOException {
        if (!jsonFile.toFile().exists()) return;

        ObjectNode root = (ObjectNode) mapper.readTree(jsonFile.toFile());
        if (meta.computeSeconds() != null) root.put("candidateComputeSeconds", meta.computeSeconds());
        if (meta.toolVersion() != null) root.put("toolVersion", meta.toolVersion());
        if (meta.toolConfig() != null) root.put("toolConfig", meta.toolConfig());

        List<Integer> emittedKs = generator != null ? generator.emittedKs() : List.of();
        JsonNode variants = root.get("variants");
        if (variants != null && variants.isArray()) {
            for (JsonNode variantNode : variants) {
                int pos = variantNode.path("variantIndex").asInt() - 1;
                if (pos < 0 || pos >= emittedKs.size()) continue;
                ExternalCandidateMeta.Candidate cand = meta.candidateForK(emittedKs.get(pos));
                if (cand == null) continue;
                ObjectNode variant = (ObjectNode) variantNode;
                variant.put("candidateK", cand.k());
                if (cand.mode() != null) variant.put("candidateMode", cand.mode());
                if (cand.strict() != null) variant.put("candidateStrict", cand.strict());
                if (cand.bestEffort() != null) variant.put("candidateBestEffort", cand.bestEffort());
                if (cand.chunksResolved() != null) variant.put("candidateChunksResolved", cand.chunksResolved());
                if (cand.chunksTotal() != null) variant.put("candidateChunksTotal", cand.chunksTotal());
                if (cand.toolFailed() != null) variant.put("candidateToolFailed", cand.toolFailed());
            }
        }

        File tmpFile = File.createTempFile(meta.mergeCommit(), ".json.tmp", jsonFile.getParent().toFile());
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmpFile, root);
            Files.move(tmpFile.toPath(), jsonFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tmpFile.delete();
            throw e;
        }
    }

    // ── utilities ────────────────────────────────────────────────────────────

    private static void copyField(ObjectNode target, String field, ObjectNode source) {
        JsonNode val = source.get(field);
        if (val != null) target.set(field, val);
    }

    private static void copyNullableField(ObjectNode target, String field, ObjectNode source) {
        JsonNode val = source.get(field);
        if (val == null || val.isNull()) target.putNull(field);
        else target.set(field, val);
    }

    /** Map the configured RQ3 best mode name (e.g. "cache_parallel") to its execution flags. */
    private static Utility.Experiments bestModeFlags() {
        String bestMode = AppConfig.getRQ3BestMode();
        return Arrays.stream(Utility.Experiments.values())
                .filter(e -> e.getName().equals(bestMode))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Unknown rq3BestMode: " + bestMode));
    }

    /** Index every fold-assigned Maven merge by commit SHA (same source as the RQ3 pipeline). */
    private Map<String, DatasetReader.MergeInfo> loadMergeIndex() throws IOException {
        Map<String, DatasetReader.MergeInfo> byCommit = new HashMap<>();
        for (DatasetReader.MergeInfo info : new JavaChunksReader().sampleRoundRobin(AppConfig.MAVEN_CONFLICTS_CSV)) {
            byCommit.put(info.getMergeCommit(), info);
        }
        return byCommit;
    }

    /**
     * Resolve the candidates root: the {@code candidatesDir} system property, or walk up
     * from the working directory looking for {@code competition/candidates} (the
     * competition checkout lives next to the cicd-oracle checkout in the workspace).
     */
    static Path resolveCandidatesRoot() throws IOException {
        String prop = System.getProperty("candidatesDir", "").trim();
        if (!prop.isEmpty()) {
            return Path.of(prop).toAbsolutePath();
        }
        File dir = Path.of("").toAbsolutePath().toFile();
        while (dir != null) {
            File candidate = new File(dir, "competition/candidates");
            if (candidate.isDirectory()) {
                return candidate.toPath();
            }
            dir = dir.getParentFile();
        }
        throw new IOException("Could not locate competition/candidates above " + Path.of("").toAbsolutePath()
                + " — pass -DcandidatesDir=/path/to/competition/candidates");
    }
}
