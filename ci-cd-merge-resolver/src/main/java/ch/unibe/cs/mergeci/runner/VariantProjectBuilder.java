package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.patterns.PatternHeuristics;
import ch.unibe.cs.mergeci.model.patterns.StrategySelector;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

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
    private final PatternHeuristics heuristics;
    private final Random random;

    public VariantProjectBuilder(Path repoPath, Path tempDir, Path projectTempDir) {
        this.repositoryPath = repoPath;
        this.tempDir = tempDir;
        this.projectName = repositoryPath.toFile().getName();
        this.projectTempDir = projectTempDir;
        this.logDir = tempDir.resolve("log");
        this.conflictPatterns = new ArrayList<>();
        try {
            this.heuristics = PatternHeuristics.loadFromResource("pattern-heuristics/learnt_historical_pattern_distribution.csv");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load pattern heuristics", e);
        }
        this.random = new Random();
    }

    /**
     * Prepare the variant generation context without writing anything to disk.
     * Variants are generated lazily (one at a time) during execution via
     * {@link VariantBuildContext#nextVariant()}, so no fixed cap is needed.
     */
    public VariantBuildContext prepareVariants(String commit1, String commit2, String mergeCommit) throws Exception {
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
        StrategySelector selector = new StrategySelector(heuristics, random);

        ObjectId branch1 = git.getRepository().resolve(commit1);
        ObjectId branch2 = git.getRepository().resolve(commit2);
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);
        Map<String, ObjectId> mergeCommitObjects = GitUtils.getObjectsFromCommit(mergeCommit, git);

        return new VariantBuildContext(
                repositoryPath,
                projectTempDir,
                projectName,
                conflictFileMap,
                totalChunks,
                selector,
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
     * Build the directory for a given variant.
     *
     * @param context      Variant build context (provides non-conflict objects)
     * @param variant      The variant project (conflict file resolutions)
     * @param variantIndex Numeric index used to name the directory
     * @return Path to the created variant directory
     */
    public Path buildVariant(VariantBuildContext context, VariantProject variant, int variantIndex) throws IOException {
        Path variantPath = projectTempDir.resolve(projectName + "_" + variantIndex);

        Git git = GitUtils.getGit(repositoryPath);
        FileUtils.saveFilesFromObjectId(variantPath, context.getNonConflictObjects(), git);

        for (ConflictFile conflictFile : variant.getClasses()) {
            File filepath = variantPath.resolve(conflictFile.getClassPath().toString()).toFile();
            if (filepath.getParentFile() != null) {
                filepath.getParentFile().mkdirs();
            }
            try (java.io.OutputStream out = new java.io.FileOutputStream(filepath)) {
                out.write(conflictFile.toString().getBytes());
            }
        }

        FileUtils.enableTestsInAllPoms(variantPath);
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
