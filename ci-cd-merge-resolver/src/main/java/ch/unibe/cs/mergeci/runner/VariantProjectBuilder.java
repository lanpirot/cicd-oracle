package ch.unibe.cs.mergeci.runner;

import ch.unibe.cs.mergeci.config.AppConfig;
import ch.unibe.cs.mergeci.model.ConflictBlock;
import ch.unibe.cs.mergeci.model.IMergeBlock;
import ch.unibe.cs.mergeci.model.VariantProject;
import ch.unibe.cs.mergeci.model.ConflictFile;
import ch.unibe.cs.mergeci.model.patterns.PatternFactory;
import ch.unibe.cs.mergeci.model.patterns.PatternHeuristics;
import ch.unibe.cs.mergeci.model.patterns.PatternStrategy;
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
            this.heuristics = PatternHeuristics.loadFromResource("pattern-heuristics/relative_numbers_summary.csv");
        } catch (Exception e) {
            throw new RuntimeException("Failed to load pattern heuristics", e);
        }
        this.random = new Random();
    }

    /**
     * Prepare variant metadata without writing to disk.
     * Returns a context that can be used to build variants on demand.
     * Uses heuristic-weighted sampling (StrategySelector) capped at MAX_VARIANTS.
     *
     * @param commit1 First parent commit
     * @param commit2 Second parent commit
     * @param mergeCommit The merge commit (human resolution)
     * @return VariantBuildContext containing all data needed to build variants
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
        List<VariantProject> projects = sampleVariants(conflictFileMap, totalChunks);

        List<Map<String, List<String>>> patterns = new ArrayList<>();
        projects.forEach(x -> patterns.add(x.extractPatterns()));

        ObjectId branch1 = git.getRepository().resolve(commit1);
        ObjectId branch2 = git.getRepository().resolve(commit2);
        Map<String, ObjectId> nonConflictObjects = GitUtils.getNonConflictObjects(git, branch1, branch2);

        Map<String, ObjectId> mergeCommitObjects = GitUtils.getObjectsFromCommit(mergeCommit, git);

        return new VariantBuildContext(
                repositoryPath,
                projectTempDir,
                projectName,
                projects,
                nonConflictObjects,
                mergeCommitObjects,
                patterns
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

    private List<VariantProject> sampleVariants(Map<String, ConflictFile> conflictFileMap, int totalChunks) {
        List<VariantProject> projects = new ArrayList<>();
        StrategySelector selector = new StrategySelector(heuristics, random);

        while (projects.size() < AppConfig.MAX_VARIANTS
                && !selector.allStrategiesExhausted(totalChunks)) {

            PatternStrategy strategy = selector.selectStrategy(totalChunks);
            if (strategy == null) break;

            List<String> assignment = selector.generateAssignment(strategy, totalChunks);

            if (assignment != null) {
                projects.add(buildProjectFromAssignment(conflictFileMap, assignment));
            } else {
                selector.recordOuterFailure();
            }
        }

        return projects;
    }

    private VariantProject buildProjectFromAssignment(Map<String, ConflictFile> conflictFileMap, List<String> assignment) {
        List<ConflictFile> resolvedClasses = new ArrayList<>();
        int chunkIndex = 0;

        for (Map.Entry<String, ConflictFile> entry : conflictFileMap.entrySet()) {
            ConflictFile cf = entry.getValue();
            List<IMergeBlock> resolvedBlocks = new ArrayList<>();

            for (IMergeBlock block : cf.getMergeBlocks()) {
                if (block instanceof ConflictBlock cb) {
                    ConflictBlock clone = cb.clone();
                    clone.setPattern(PatternFactory.fromName(assignment.get(chunkIndex++)));
                    resolvedBlocks.add(clone);
                } else {
                    resolvedBlocks.add(block);
                }
            }

            ConflictFile resolvedCf = new ConflictFile();
            resolvedCf.setClassPath(cf.getClassPath());
            resolvedCf.setMergeBlocks(resolvedBlocks);
            resolvedClasses.add(resolvedCf);
        }

        VariantProject project = new VariantProject();
        project.setProjectPath(repositoryPath);
        project.setClasses(resolvedClasses);
        return project;
    }

    /**
     * Build the main project directory (human-resolved merge).
     *
     * @param context Variant build context
     * @return Path to the main project directory
     */
    public Path buildMainProject(VariantBuildContext context) throws IOException {
        Path mainProjectPath = projectTempDir.resolve(projectName);
        Git git = GitUtils.getGit(repositoryPath);
        FileUtils.saveFilesFromObjectId(mainProjectPath, context.getMergeCommitObjects(), git);
        return mainProjectPath;
    }

    /**
     * Build a specific variant directory.
     *
     * @param context Variant build context
     * @param variantIndex Index of the variant to build (0-based)
     * @return Path to the variant directory
     */
    public Path buildVariant(VariantBuildContext context, int variantIndex) throws IOException {
        if (variantIndex < 0 || variantIndex >= context.getVariantCount()) {
            throw new IllegalArgumentException("Invalid variant index: " + variantIndex);
        }

        Path variantPath = projectTempDir.resolve(projectName + "_" + variantIndex);
        VariantProject project = context.getVariant(variantIndex);

        Git git = GitUtils.getGit(repositoryPath);
        FileUtils.saveFilesFromObjectId(variantPath, context.getNonConflictObjects(), git);

        for (ConflictFile conflictFile : project.getClasses()) {
            File filepath = variantPath.resolve(conflictFile.getClassPath().toString()).toFile();
            if (filepath.getParentFile() != null) {
                filepath.getParentFile().mkdirs();
            }
            try (java.io.OutputStream out = new java.io.FileOutputStream(filepath)) {
                out.write(conflictFile.toString().getBytes());
            }
        }

        return variantPath;
    }

    /**
     * Run tests with just-in-time directory creation and immediate cleanup.
     * Variant directories are built on-demand and deleted immediately after testing.
     *
     * @param context Variant build context
     * @param runner Just-in-time runner
     * @return Execution time statistics
     */
    public ExperimentTiming runTestsJustInTime(VariantBuildContext context, IJustInTimeRunner runner) throws Exception {
        // Populate conflictPatterns for backward compatibility with result collectors
        this.conflictPatterns = new ArrayList<>(context.getConflictPatterns());
        return runner.run(context, this);
    }

    /**
     * Collect compilation result from a single project directory.
     *
     * @param projectKey Project key (e.g., "projectName" or "projectName_0")
     * @return CompilationResult, or empty Optional if log doesn't exist
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
     *
     * @param projectKey Project key (e.g., "projectName" or "projectName_0")
     * @param projectPath Path to the project directory
     * @return TestTotal for the project
     */
    public TestTotal collectTestResult(String projectKey, Path projectPath) {
        return new TestTotal(projectPath.toFile());
    }
}
