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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.internal.storage.file.WindowCache;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.storage.file.WindowCacheConfig;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

        int processed = 0, skipped = 0;
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
            if (!humanBaselineDir.resolve(mergeCommit + AppConfig.JSON).toFile().exists()) {
                System.err.println("  SKIPPED: no RQ3 human_baseline JSON (merge was never scored by RQ3)");
                skipped++;
                continue;
            }

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
                // Same per-(merge, mode) cleanup as RQPipelineRunner.runModes
                new MavenCommandResolver(AppConfig.USE_MAVEN_DAEMON).stopDaemons();
                FileUtils.deleteQuietly(AppConfig.SHARED_CACHE_DIR.toFile());
                OverlayMount.cleanupStaleMounts(AppConfig.OVERLAY_TMP_DIR.resolve("projects"));
                OverlayMount.cleanupStaleMounts(MavenExecutionFactory.M2_OVERLAY_DIR);
                RepositoryCache.clear();
                WindowCache.reconfigure(new WindowCacheConfig());
            }

            annotateResult(modeDir.resolve(mergeCommit + AppConfig.JSON), meta, factory.getLastCreated());
        }

        System.out.printf("%nExternal candidates finished: %d merge(s) processed, %d skipped.%n",
                processed, skipped);
    }

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
        if (!jsonFile.toFile().exists()) return; // merge was skipped, nothing to annotate

        com.fasterxml.jackson.databind.node.ObjectNode root =
                (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(jsonFile.toFile());
        if (meta.computeSeconds() != null) root.put("candidateComputeSeconds", meta.computeSeconds());
        if (meta.toolVersion() != null) root.put("toolVersion", meta.toolVersion());
        if (meta.toolConfig() != null) root.put("toolConfig", meta.toolConfig());

        List<Integer> emittedKs = generator != null ? generator.emittedKs() : List.of();
        com.fasterxml.jackson.databind.JsonNode variants = root.get("variants");
        if (variants != null && variants.isArray()) {
            for (com.fasterxml.jackson.databind.JsonNode variantNode : variants) {
                int pos = variantNode.path("variantIndex").asInt() - 1;
                if (pos < 0 || pos >= emittedKs.size()) continue;
                ExternalCandidateMeta.Candidate cand = meta.candidateForK(emittedKs.get(pos));
                if (cand == null) continue;
                com.fasterxml.jackson.databind.node.ObjectNode variant =
                        (com.fasterxml.jackson.databind.node.ObjectNode) variantNode;
                variant.put("candidateK", cand.k());
                if (cand.mode() != null) variant.put("candidateMode", cand.mode());
                if (cand.strict() != null) variant.put("candidateStrict", cand.strict());
                if (cand.bestEffort() != null) variant.put("candidateBestEffort", cand.bestEffort());
                if (cand.chunksResolved() != null) variant.put("candidateChunksResolved", cand.chunksResolved());
                if (cand.chunksTotal() != null) variant.put("candidateChunksTotal", cand.chunksTotal());
                if (cand.toolFailed() != null) variant.put("candidateToolFailed", cand.toolFailed());
            }
        }

        // Atomic write, mirroring JsonResultWriter
        File tmpFile = File.createTempFile(meta.mergeCommit(), ".json.tmp", jsonFile.getParent().toFile());
        try {
            mapper.writerWithDefaultPrettyPrinter().writeValue(tmpFile, root);
            Files.move(tmpFile.toPath(), jsonFile,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            tmpFile.delete();
            throw e;
        }
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
