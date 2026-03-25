package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.experiment.MlAutoregressivePredictor;
import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.runner.maven.CompilationResult;
import ch.unibe.cs.mergeci.runner.maven.TestTotal;
import ch.unibe.cs.mergeci.util.FileUtils;
import ch.unibe.cs.mergeci.util.GitUtils;
import ch.unibe.cs.mergeci.util.ProjectBuilderUtils;
import lombok.Getter;
import lombok.Setter;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.Sequence;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.merge.MergeResult;
import org.eclipse.jgit.merge.ResolveMerger;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Getter
@Setter
public class VariantProjectBuilder {
    private final Path repositoryPath;
    private final Path tempDir;
    private final Path projectTempDir;
    private final String projectName;
    private final Path logDir;
    private List<Map<String, List<String>>> conflictPatterns;
    private boolean isVerbose = false;
    private Map<String, Integer> foldAssignment; // loaded lazily in prepareVariants()

    public VariantProjectBuilder(Path repoPath, Path tempDir, Path projectTempDir) {
        this.repositoryPath = repoPath;
        this.tempDir = tempDir;
        this.projectName = repositoryPath.toFile().getName();
        this.projectTempDir = projectTempDir;
        this.logDir = tempDir.resolve("log");
        this.conflictPatterns = new ArrayList<>();
    }

    /**
     * Prepare the variant generation context without writing anything to disk.
     * Variants are generated lazily (one at a time) during execution via
     * {@link VariantBuildContext#nextVariant()}, so no fixed cap is needed.
     */
    public VariantBuildContext prepareVariants(String commit1, String commit2, String mergeCommit) throws Exception {
        return prepareVariantsInternal(commit1, commit2, mergeCommit, null, null);
    }

    /**
     * Prepare the variant generation context with an explicit {@link IVariantGenerator}.
     * The generator takes precedence over any ML-AR predictions that might be loaded.
     */
    public VariantBuildContext prepareVariants(String commit1, String commit2, String mergeCommit,
                                               IVariantGenerator generator) throws Exception {
        return prepareVariantsInternal(commit1, commit2, mergeCommit, null, generator);
    }

    /**
     * Prepare the variant generation context without writing anything to disk.
     *
     * @param mergeId numeric merge_id from Java_chunks.csv; used to select the correct
     *                ML cross-validation fold. Pass {@code null} for test repos or any
     *                merge not sourced from Java_chunks.csv — ML-AR predictions are
     *                skipped and the context will produce no variants.
     */
    public VariantBuildContext prepareVariants(String commit1, String commit2, String mergeCommit,
                                               String mergeId) throws Exception {
        return prepareVariantsInternal(commit1, commit2, mergeCommit, mergeId, null);
    }

    private VariantBuildContext prepareVariantsInternal(String commit1, String commit2, String mergeCommit,
                                                        String mergeId, IVariantGenerator generator) throws Exception {
        Git git = GitUtils.getGit(repositoryPath);
        ResolveMerger merger = GitUtils.makeMerge(commit1, commit2, git);
        Map<String, MergeResult<? extends Sequence>> mergeResultMap = GitUtils.getMergeResults(merger);

        if (isVerbose) {
            System.out.println("conflicts :");
            mergeResultMap.keySet().forEach(System.out::println);
        }

        Map<String, ConflictFile> conflictFileMap = new LinkedHashMap<>();
        for (Map.Entry<String, MergeResult<? extends Sequence>> entry : mergeResultMap.entrySet()) {
            conflictFileMap.put(entry.getKey(), ProjectBuilderUtils.getProjectClass(entry.getValue(), entry.getKey()));
        }

        int totalChunks = countConflictChunks(conflictFileMap);

        List<List<String>> mlPredictions = List.of();
        if (generator == null && mergeId != null && !mergeId.isEmpty()) {
            if (foldAssignment == null) {
                try {
                    foldAssignment = MlAutoregressivePredictor.loadFoldAssignment(AppConfig.RQ1_FOLD_ASSIGNMENT_FILE);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to load ML-AR fold assignment from "
                            + AppConfig.RQ1_FOLD_ASSIGNMENT_FILE, e);
                }
            }
            MlAutoregressivePredictor mlPredictor = MlAutoregressivePredictor.forMerge(
                    mergeId, foldAssignment, AppConfig.RQ1_PREDICTIONS_DIR, AppConfig.ML_VARIANT_CAP);
            mlPredictions = mlPredictor.getPredictions(mergeId);
        }

        ObjectId branch1 = git.getRepository().resolve(commit1);
        ObjectId branch2 = git.getRepository().resolve(commit2);
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);
        Map<String, ObjectId> mergeCommitObjects = GitUtils.getObjectsFromCommit(mergeCommit, git);

        return new VariantBuildContext(
                repositoryPath,
                projectTempDir,
                projectName,
                mergeCommit,
                conflictFileMap,
                totalChunks,
                mlPredictions,
                generator,
                nonConflictObjects,
                mergeCommitObjects
        );
    }

    private static int countConflictChunks(Map<String, ConflictFile> conflictFileMap) {
        int count = 0;
        for (ConflictFile cf : conflictFileMap.values()) {
            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Build the main project directory (human-resolved merge).
     */
    public Path buildMainProject(VariantBuildContext context) throws IOException {
        Path mainProjectPath = projectTempDir.resolve(projectName);
        Git git = GitUtils.getGit(repositoryPath);
        FileUtils.saveFilesFromObjectId(mainProjectPath, context.getMergeCommitObjects(), git);
        FileUtils.enableTestsInAllPoms(mainProjectPath);
        return mainProjectPath;
    }

    /**
     * Build the non-conflict portion of a variant directory (source files from git, pom patches).
     * The conflict-resolved files are NOT written yet — call {@link #applyConflictResolution}
     * after any cache copying to achieve the T-1 < T0 < T1 timestamp ordering that Maven's
     * incremental compiler needs to correctly skip unchanged modules and recompile changed ones.
     */
    public Path buildVariantBase(VariantBuildContext context, int variantIndex) throws IOException {
        Path variantPath = projectTempDir.resolve(projectName + "_" + variantIndex);
        Git git = GitUtils.getGit(repositoryPath);
        FileUtils.saveFilesFromObjectId(variantPath, context.getNonConflictObjects(), git);
        FileUtils.enableTestsInAllPoms(variantPath);
        return variantPath;
    }

    /**
     * Write the conflict-resolved source files into an already-prepared variant directory.
     * Must be called after any cache copy (target/ dirs) so that conflict files receive a
     * timestamp T1 that is strictly newer than the copied class files (T0 > T-1).
     */
    public void applyConflictResolution(Path variantPath, VariantProject variant) throws IOException {
        for (ConflictFile conflictFile : variant.getClasses()) {
            File filepath = variantPath.resolve(conflictFile.getClassPath().toString()).toFile();
            if (filepath.getParentFile() != null) {
                filepath.getParentFile().mkdirs();
            }
            try (java.io.OutputStream out = new java.io.FileOutputStream(filepath)) {
                out.write(conflictFile.toString().getBytes());
            }
        }
    }

    /**
     * Build the directory for a given variant (non-cache paths).
     * Equivalent to {@link #buildVariantBase} followed immediately by {@link #applyConflictResolution}.
     */
    public Path buildVariant(VariantBuildContext context, VariantProject variant, int variantIndex) throws IOException {
        Path variantPath = buildVariantBase(context, variantIndex);
        applyConflictResolution(variantPath, variant);
        return variantPath;
    }

    /**
     * Run tests with just-in-time directory creation and immediate cleanup.
     * Variant directories are built on-demand and deleted immediately after testing.
     */
    public ExperimentTiming runTestsJustInTime(VariantBuildContext context, IJustInTimeRunner runner) throws Exception {
        // Point conflictPatterns at the context's live list — it grows as variants are
        // generated lazily during execution, so result collectors see the full set.
        this.conflictPatterns = context.getConflictPatterns();
        return runner.run(context, this);
    }

    /**
     * Collect compilation result from a single project directory.
     */
    public Optional<CompilationResult> collectCompilationResult(String projectKey) throws IOException {
        Path logPath = logDir.resolve(projectKey + "_compilation");
        if (logPath.toFile().exists()) {
            return Optional.of(new CompilationResult(logPath));
        }
        return Optional.empty();
    }

    /**
     * Collect test result from a single project directory.
     */
    public TestTotal collectTestResult(Path projectPath) {
        return new TestTotal(projectPath.toFile());
    }
}
